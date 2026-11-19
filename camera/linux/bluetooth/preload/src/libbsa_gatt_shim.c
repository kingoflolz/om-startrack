#define _GNU_SOURCE

/*
 * LD_PRELOAD GATT broker for the Olympus blebridge/libbsa combination.
 *
 * libbsa has one process-global GATT-server callback even though BSA exposes
 * several server application slots.  This DSO keeps that callback permanently
 * pointed at bsa_ble_mux(), forwards stock events to blebridge's callback, and
 * leases one additional server application to a same-UID Unix-socket client.
 * Normal extension-side BSA calls run on the single broker pthread.  The
 * callback mux may issue an immediate ATT error when fail-closed delivery of
 * a read/write request is required.
 */

#include "bsa_api.h"
#include "bsa_gatt_proxy_protocol.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

#define EVENT_QUEUE_CAPACITY 32u
#define SERVICE_CAPACITY 16u
#define ATTRIBUTE_CAPACITY 64u
#define CONNECTION_CAPACITY 8u
#define PENDING_ATT_CAPACITY 16u
#define CLEANUP_TIMEOUT_MS 5000L
#define SOCKET_IO_TIMEOUT_MS 5000

struct real_api {
    tBSA_STATUS (*mgt_open)(tBSA_MGT_OPEN *);
    tBSA_STATUS (*mgt_close)(tBSA_MGT_CLOSE *);
    tBSA_STATUS (*ble_enable)(tBSA_BLE_ENABLE *);
    tBSA_STATUS (*ble_disable)(tBSA_BLE_DISABLE *);
    tBSA_STATUS (*app_register)(tBSA_BLE_SE_REGISTER *);
    tBSA_STATUS (*app_deregister)(tBSA_BLE_SE_DEREGISTER *);
    tBSA_STATUS (*create_service)(tBSA_BLE_SE_CREATE *);
    tBSA_STATUS (*add_attribute)(tBSA_BLE_SE_ADDCHAR *);
    tBSA_STATUS (*start_service)(tBSA_BLE_SE_START *);
    tBSA_STATUS (*stop_service)(tBSA_BLE_SE_STOP *);
    tBSA_STATUS (*send_response)(tBSA_BLE_SE_SENDRSP *);
    tBSA_STATUS (*send_indication)(tBSA_BLE_SE_SENDIND *);
};

struct queued_frame {
    uint16_t type;
    uint32_t request_id;
    uint32_t length;
    uint8_t payload[BGP_MAX_PAYLOAD];
};

struct service_record {
    uint16_t id;
    uint8_t used;
    uint8_t started;
};

struct attribute_record {
    uint16_t id;
    uint8_t used;
};

struct connection_record {
    uint16_t id;
    uint8_t used;
};

struct pending_att {
    uint32_t cookie;
    uint32_t epoch;
    uint32_t trans_id;
    uint16_t conn_id;
    uint16_t handle;
    uint16_t offset;
    uint8_t used;
};

struct shim_state {
    pthread_mutex_t mutex;
    pthread_mutex_t lifecycle_mutex;
    pthread_cond_t callback_cond;
    pthread_once_t symbols_once;
    struct real_api api;
    int symbols_ok;

    tBSA_MGT_CBACK *stock_mgt_callback;
    tBSA_BLE_CBACK *stock_ble_callback;
    uint8_t stock_server_if;

    uint8_t mgt_open;
    uint8_t ble_enabled;
    uint8_t stock_registered;
    uint8_t stock_started;
    unsigned int lifecycle_calls;
    uint32_t epoch;
    uint32_t mgt_disconnect_generation;

    uint8_t extension_registered;
    uint8_t extension_server_if;
    uint16_t structural_pending;
    uint8_t cleanup_in_progress;
    struct service_record services[SERVICE_CAPACITY];
    struct attribute_record attributes[ATTRIBUTE_CAPACITY];
    struct connection_record connections[CONNECTION_CAPACITY];
    struct pending_att pending[PENDING_ATT_CAPACITY];
    uint32_t next_cookie;

    int worker_started;
    int wake_pipe[2];
    int client_fd;
    struct queued_frame queue[EVENT_QUEUE_CAPACITY];
    unsigned int queue_head;
    unsigned int queue_count;
};

static struct shim_state shim = {
    .mutex = PTHREAD_MUTEX_INITIALIZER,
    .lifecycle_mutex = PTHREAD_MUTEX_INITIALIZER,
    .callback_cond = PTHREAD_COND_INITIALIZER,
    .symbols_once = PTHREAD_ONCE_INIT,
    .stock_server_if = BSA_BLE_INVALID_IF,
    .epoch = 1u,
    .extension_server_if = BSA_BLE_INVALID_IF,
    .next_cookie = 1u,
    .wake_pipe = {-1, -1},
    .client_fd = -1
};

static void bsa_mgt_mux(tBSA_MGT_EVT event, tBSA_MGT_MSG *message);
static void bsa_ble_mux(tBSA_BLE_EVT event, tBSA_BLE_MSG *message);
static void *broker_worker(void *unused);

/*
 * The loader has already mapped this DSO when constructors run.  Remove the
 * dedicated launch variable before stock blebridge can invoke system() so
 * start-stop-daemon, bsa_server, and other helpers do not inherit the shim.
 * Camera deployment uses this DSO as the sole LD_PRELOAD entry.
 */
__attribute__((constructor)) static void prevent_preload_inheritance(void)
{
    const char *preload = getenv("LD_PRELOAD");

    if (preload != NULL && strstr(preload, "libbsa_gatt_shim.so") != NULL)
        (void)unsetenv("LD_PRELOAD");
}

static void load_symbol(void *destination, const char *name, int *ok)
{
    void *symbol = dlsym(RTLD_NEXT, name);

    if (symbol == NULL) {
        *ok = 0;
        return;
    }
    memcpy(destination, &symbol, sizeof(symbol));
}

static void resolve_symbols_once(void)
{
    int ok = 1;

#define LOAD(member, name) \
    load_symbol(&shim.api.member, name, &ok)
    LOAD(mgt_open, "BSA_MgtOpen");
    LOAD(mgt_close, "BSA_MgtClose");
    LOAD(ble_enable, "BSA_BleEnable");
    LOAD(ble_disable, "BSA_BleDisable");
    LOAD(app_register, "BSA_BleSeAppRegister");
    LOAD(app_deregister, "BSA_BleSeAppDeregister");
    LOAD(create_service, "BSA_BleSeCreateService");
    LOAD(add_attribute, "BSA_BleSeAddChar");
    LOAD(start_service, "BSA_BleSeStartService");
    LOAD(stop_service, "BSA_BleSeStopService");
    LOAD(send_response, "BSA_BleSeSendRsp");
    LOAD(send_indication, "BSA_BleSeSendInd");
#undef LOAD
    shim.symbols_ok = ok;
}

static int resolve_symbols(void)
{
    (void)pthread_once(&shim.symbols_once, resolve_symbols_once);
    return shim.symbols_ok;
}

static int operation_epoch_current(uint32_t epoch)
{
    int current;

    (void)pthread_mutex_lock(&shim.mutex);
    current = shim.epoch == epoch;
    (void)pthread_mutex_unlock(&shim.mutex);
    return current;
}

static uint32_t state_flags_locked(void)
{
    uint32_t flags = 0u;

    if (shim.mgt_open)
        flags |= BGP_STATE_MGT_OPEN;
    if (shim.ble_enabled)
        flags |= BGP_STATE_BLE_ENABLED;
    if (shim.stock_registered)
        flags |= BGP_STATE_STOCK_APP_REGISTERED;
    if (shim.stock_started)
        flags |= BGP_STATE_STOCK_APP_STARTED;
    if (shim.client_fd >= 0)
        flags |= BGP_STATE_OWNER_CONNECTED;
    if (shim.extension_registered)
        flags |= BGP_STATE_EXTENSION_REGISTERED;
    return flags;
}

static void wake_worker_locked(void)
{
    uint8_t byte = 1u;
    ssize_t count;

    if (shim.wake_pipe[1] < 0)
        return;
    do {
        count = write(shim.wake_pipe[1], &byte, sizeof(byte));
    } while (count < 0 && errno == EINTR);
}

static int queue_frame_locked(uint16_t type, uint32_t request_id,
                              const void *payload, size_t length)
{
    unsigned int tail;
    struct queued_frame *frame;

    if (shim.client_fd < 0 || length > BGP_MAX_PAYLOAD ||
        shim.queue_count == EVENT_QUEUE_CAPACITY)
        return -1;
    tail = (shim.queue_head + shim.queue_count) % EVENT_QUEUE_CAPACITY;
    frame = &shim.queue[tail];
    frame->type = type;
    frame->request_id = request_id;
    frame->length = (uint32_t)length;
    if (length != 0u)
        memcpy(frame->payload, payload, length);
    shim.queue_count++;
    wake_worker_locked();
    return 0;
}

static void queue_state_locked(int32_t reason, tBSA_STATUS bsa_status)
{
    struct bgp_state_event event;

    memset(&event, 0, sizeof(event));
    event.state_flags = state_flags_locked();
    event.epoch = shim.epoch;
    event.reason = reason;
    event.bsa_status = bsa_status;
    if (queue_frame_locked(BGP_EVT_STATE, 0u, &event, sizeof(event)) != 0 &&
        shim.client_fd >= 0) {
        /* State loss is ambiguous to the lease owner, so fail closed. */
        (void)shutdown(shim.client_fd, SHUT_RDWR);
    }
}

static int service_index_locked(uint16_t id)
{
    unsigned int index;

    for (index = 0u; index < SERVICE_CAPACITY; index++) {
        if (shim.services[index].used && shim.services[index].id == id)
            return (int)index;
    }
    return -1;
}

static int any_service_started_locked(void)
{
    unsigned int index;

    for (index = 0u; index < SERVICE_CAPACITY; index++) {
        if (shim.services[index].used && shim.services[index].started)
            return 1;
    }
    return 0;
}

static int begin_structural_locked(uint16_t request_type)
{
    if (shim.structural_pending != 0u)
        return -1;
    shim.structural_pending = request_type;
    return 0;
}

static void finish_structural_locked(uint16_t request_type)
{
    if (shim.structural_pending == request_type)
        shim.structural_pending = 0u;
    (void)pthread_cond_broadcast(&shim.callback_cond);
    wake_worker_locked();
}

static void finish_failed_structural_call(uint16_t request_type,
                                          tBSA_STATUS status)
{
    if (status == BSA_SUCCESS)
        return;
    (void)pthread_mutex_lock(&shim.mutex);
    finish_structural_locked(request_type);
    (void)pthread_mutex_unlock(&shim.mutex);
}

static int attribute_owned_locked(uint16_t id)
{
    unsigned int index;

    for (index = 0u; index < ATTRIBUTE_CAPACITY; index++) {
        if (shim.attributes[index].used && shim.attributes[index].id == id)
            return 1;
    }
    return 0;
}

static int connection_owned_locked(uint16_t id)
{
    unsigned int index;

    for (index = 0u; index < CONNECTION_CAPACITY; index++) {
        if (shim.connections[index].used && shim.connections[index].id == id)
            return 1;
    }
    return 0;
}

static int remember_service_locked(uint16_t id)
{
    unsigned int index;

    if (service_index_locked(id) >= 0)
        return 0;
    for (index = 0u; index < SERVICE_CAPACITY; index++) {
        if (!shim.services[index].used) {
            shim.services[index].used = 1u;
            shim.services[index].started = 0u;
            shim.services[index].id = id;
            return 0;
        }
    }
    return -1;
}

static int remember_attribute_locked(uint16_t id)
{
    unsigned int index;

    if (attribute_owned_locked(id))
        return 0;
    for (index = 0u; index < ATTRIBUTE_CAPACITY; index++) {
        if (!shim.attributes[index].used) {
            shim.attributes[index].used = 1u;
            shim.attributes[index].id = id;
            return 0;
        }
    }
    return -1;
}

static int remember_connection_locked(uint16_t id)
{
    unsigned int index;

    if (connection_owned_locked(id))
        return 0;
    for (index = 0u; index < CONNECTION_CAPACITY; index++) {
        if (!shim.connections[index].used) {
            shim.connections[index].used = 1u;
            shim.connections[index].id = id;
            return 0;
        }
    }
    return -1;
}

static void forget_connection_locked(uint16_t id)
{
    unsigned int index;

    for (index = 0u; index < CONNECTION_CAPACITY; index++) {
        if (shim.connections[index].used && shim.connections[index].id == id)
            memset(&shim.connections[index], 0, sizeof(shim.connections[index]));
    }
}

static unsigned int take_pending_for_connection_locked(
    uint16_t conn_id, struct pending_att *result)
{
    unsigned int count = 0u;
    unsigned int index;

    for (index = 0u; index < PENDING_ATT_CAPACITY; index++) {
        if (shim.pending[index].used &&
            shim.pending[index].conn_id == conn_id) {
            result[count++] = shim.pending[index];
            memset(&shim.pending[index], 0, sizeof(shim.pending[index]));
        }
    }
    return count;
}

static void clear_extension_locked(void)
{
    shim.extension_registered = 0u;
    shim.extension_server_if = BSA_BLE_INVALID_IF;
    shim.structural_pending = 0u;
    memset(shim.services, 0, sizeof(shim.services));
    memset(shim.attributes, 0, sizeof(shim.attributes));
    memset(shim.connections, 0, sizeof(shim.connections));
    memset(shim.pending, 0, sizeof(shim.pending));
    (void)pthread_cond_broadcast(&shim.callback_cond);
    wake_worker_locked();
}

static void invalidate_facade_locked(int32_t reason, tBSA_STATUS status)
{
    int fd = shim.client_fd;

    /* Prevent descriptor reuse before the worker observes the revocation. */
    if (fd >= 0)
        (void)shutdown(fd, SHUT_RDWR);
    shim.epoch++;
    clear_extension_locked();
    shim.queue_head = 0u;
    shim.queue_count = 0u;
    shim.client_fd = -1;
    (void)reason;
    (void)status;
    wake_worker_locked();
}

static uint32_t begin_lifecycle_call(void)
{
    uint32_t epoch;

    (void)pthread_mutex_lock(&shim.lifecycle_mutex);
    (void)pthread_mutex_lock(&shim.mutex);
    epoch = shim.epoch;
    shim.lifecycle_calls++;
    (void)pthread_mutex_unlock(&shim.mutex);
    return epoch;
}

static void finish_lifecycle_call(uint32_t epoch, tBSA_STATUS status,
                                  int management_close)
{
    (void)pthread_mutex_lock(&shim.mutex);
    if (shim.lifecycle_calls != 0u)
        shim.lifecycle_calls--;
    if (status == BSA_SUCCESS) {
        if (management_close)
            shim.mgt_open = 0u;
        shim.ble_enabled = 0u;
        shim.stock_registered = 0u;
        shim.stock_started = 0u;
        shim.stock_server_if = BSA_BLE_INVALID_IF;
        if (shim.epoch == epoch) {
            invalidate_facade_locked(
                management_close ? BGP_STATUS_BAD_STATE
                                 : BGP_STATUS_NOT_READY,
                management_close ? BSA_ERROR_CLI_NOT_CONNECTED
                                 : BSA_ERROR_SRV_BLUETOOTH_DISABLE);
        }
    }
    (void)pthread_cond_broadcast(&shim.callback_cond);
    (void)pthread_mutex_unlock(&shim.mutex);
    (void)pthread_mutex_unlock(&shim.lifecycle_mutex);
}

static void finish_nondestructive_lifecycle_call(void)
{
    (void)pthread_mutex_lock(&shim.mutex);
    if (shim.lifecycle_calls != 0u)
        shim.lifecycle_calls--;
    (void)pthread_cond_broadcast(&shim.callback_cond);
    (void)pthread_mutex_unlock(&shim.mutex);
    (void)pthread_mutex_unlock(&shim.lifecycle_mutex);
}

static uint32_t allocate_pending_locked(const tBSA_BLE_SE_READ_MSG *read,
                                        const tBSA_BLE_SE_WRITE_MSG *write)
{
    unsigned int index;
    struct pending_att *pending;

    for (index = 0u; index < PENDING_ATT_CAPACITY; index++) {
        if (!shim.pending[index].used)
            break;
    }
    if (index == PENDING_ATT_CAPACITY)
        return 0u;
    pending = &shim.pending[index];
    memset(pending, 0, sizeof(*pending));
    pending->used = 1u;
    pending->epoch = shim.epoch;
    pending->cookie = shim.next_cookie++;
    if (pending->cookie == 0u)
        pending->cookie = shim.next_cookie++;
    if (read != NULL) {
        pending->trans_id = read->trans_id;
        pending->conn_id = read->conn_id;
        pending->handle = read->handle;
        pending->offset = read->offset;
    } else {
        pending->trans_id = write->trans_id;
        pending->conn_id = write->conn_id;
        pending->handle = write->handle;
        pending->offset = write->offset;
    }
    return pending->cookie;
}

static int take_pending_locked(uint32_t cookie, struct pending_att *result)
{
    unsigned int index;

    for (index = 0u; index < PENDING_ATT_CAPACITY; index++) {
        if (shim.pending[index].used && shim.pending[index].cookie == cookie &&
            shim.pending[index].epoch == shim.epoch) {
            *result = shim.pending[index];
            memset(&shim.pending[index], 0, sizeof(shim.pending[index]));
            return 0;
        }
    }
    return -1;
}

static void send_att_error(uint16_t conn_id, uint32_t trans_id,
                           uint16_t handle, uint16_t offset, uint8_t status)
{
    tBSA_BLE_SE_SENDRSP response;

    if (!resolve_symbols())
        return;
    memset(&response, 0, sizeof(response));
    response.conn_id = conn_id;
    response.trans_id = (uint16_t)trans_id;
    response.status = status;
    response.handle = handle;
    response.offset = offset;
    (void)shim.api.send_response(&response);
}

static int uuid_to_bsa(const struct bgp_uuid *wire, tBT_UUID *uuid)
{
    unsigned int index;

    memset(uuid, 0, sizeof(*uuid));
    if (wire->reserved != 0u)
        return -1;
    uuid->len = wire->len;
    if (wire->len == LEN_UUID_16) {
        uuid->uu.uuid16 = (uint16_t)(((uint16_t)wire->value[0] << 8) |
                                    wire->value[1]);
    } else if (wire->len == LEN_UUID_32) {
        uuid->uu.uuid32 = ((uint32_t)wire->value[0] << 24) |
                          ((uint32_t)wire->value[1] << 16) |
                          ((uint32_t)wire->value[2] << 8) |
                          wire->value[3];
    } else if (wire->len == LEN_UUID_128) {
        for (index = 0u; index < 16u; index++)
            uuid->uu.uuid128[index] = wire->value[15u - index];
    } else {
        return -1;
    }
    return 0;
}

static int write_exact(int fd, const void *buffer, size_t length)
{
    const uint8_t *bytes = buffer;
    size_t offset = 0u;

    while (offset < length) {
        struct pollfd descriptor;
        ssize_t count;

        descriptor.fd = fd;
        descriptor.events = POLLOUT;
        descriptor.revents = 0;
        do {
            count = poll(&descriptor, 1u, SOCKET_IO_TIMEOUT_MS);
        } while (count < 0 && errno == EINTR);
        if (count <= 0 || (descriptor.revents & POLLOUT) == 0)
            return -1;
        count = send(fd, bytes + offset, length - offset,
                     MSG_NOSIGNAL | MSG_DONTWAIT);

        if (count < 0 && errno == EINTR)
            continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            continue;
        if (count <= 0)
            return -1;
        offset += (size_t)count;
    }
    return 0;
}

static int read_exact(int fd, void *buffer, size_t length)
{
    uint8_t *bytes = buffer;
    size_t offset = 0u;

    while (offset < length) {
        struct pollfd descriptor;
        ssize_t count;

        descriptor.fd = fd;
        descriptor.events = POLLIN;
        descriptor.revents = 0;
        do {
            count = poll(&descriptor, 1u, SOCKET_IO_TIMEOUT_MS);
        } while (count < 0 && errno == EINTR);
        if (count <= 0 || (descriptor.revents & POLLIN) == 0)
            return -1;
        count = recv(fd, bytes + offset, length - offset, MSG_DONTWAIT);

        if (count < 0 && errno == EINTR)
            continue;
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            continue;
        if (count <= 0)
            return -1;
        offset += (size_t)count;
    }
    return 0;
}

static int send_frame(int fd, uint16_t type, uint32_t request_id,
                      const void *payload, size_t length)
{
    struct bgp_frame_header header;

    if (length > BGP_MAX_PAYLOAD)
        return -1;
    memset(&header, 0, sizeof(header));
    header.magic = BGP_MAGIC;
    header.version = BGP_VERSION;
    header.type = type;
    header.request_id = request_id;
    header.payload_len = (uint32_t)length;
    if (write_exact(fd, &header, sizeof(header)) != 0)
        return -1;
    return length == 0u ? 0 : write_exact(fd, payload, length);
}

static struct bgp_result result_value(int32_t proxy_status,
                                      tBSA_STATUS bsa_status)
{
    struct bgp_result result;

    memset(&result, 0, sizeof(result));
    result.proxy_status = proxy_status;
    result.bsa_status = bsa_status;
    return result;
}

static int ready_for_extension_locked(void)
{
    return shim.mgt_open && shim.ble_enabled && shim.stock_registered &&
           shim.stock_started && shim.lifecycle_calls == 0u;
}

static int extension_operational_locked(void)
{
    return shim.mgt_open && shim.ble_enabled && shim.extension_registered &&
           shim.lifecycle_calls == 0u;
}

static struct bgp_result handle_generic_command(uint16_t type,
                                                 const uint8_t *payload,
                                                 uint32_t length)
{
    tBSA_STATUS status = BSA_SUCCESS;
    int owned;
    uint32_t operation_epoch = 0u;

    if (!resolve_symbols())
        return result_value(BGP_STATUS_BAD_STATE, BSA_ERROR_CLI_INTERNAL);

    switch (type) {
    case BGP_REQ_DEREGISTER: {
        const struct bgp_deregister_request *wire;
        tBSA_BLE_SE_DEREGISTER request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_deregister_request *)payload;
        if (wire->reserved0 != 0u || wire->reserved1 != 0u)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        operation_epoch = shim.epoch;
        owned = shim.extension_registered &&
                shim.extension_server_if == wire->server_if;
        if (owned && !extension_operational_locked())
            owned = -3;
        else if (owned && any_service_started_locked())
            owned = -1;
        else if (owned && begin_structural_locked(type) != 0)
            owned = -2;
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == 0)
            return result_value(BGP_STATUS_NOT_OWNER, 0u);
        if (owned == -1)
            return result_value(BGP_STATUS_BAD_STATE, 0u);
        if (owned == -2)
            return result_value(BGP_STATUS_BUSY, 0u);
        if (owned == -3)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        memset(&request, 0, sizeof(request));
        request.server_if = wire->server_if;
        status = shim.api.app_deregister(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        finish_failed_structural_call(type, status);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    case BGP_REQ_CREATE_SERVICE: {
        const struct bgp_create_service_request *wire;
        tBSA_BLE_SE_CREATE request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_create_service_request *)payload;
        if (wire->reserved0 != 0u || wire->reserved1 != 0u ||
            wire->is_primary > 1u)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        memset(&request, 0, sizeof(request));
        if (wire->num_handles == 0u ||
            uuid_to_bsa(&wire->service_uuid, &request.service_uuid) != 0)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        operation_epoch = shim.epoch;
        owned = shim.extension_registered &&
                shim.extension_server_if == wire->server_if;
        if (owned && !extension_operational_locked())
            owned = -2;
        else if (owned && begin_structural_locked(type) != 0)
            owned = -1;
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == 0)
            return result_value(BGP_STATUS_NOT_OWNER, 0u);
        if (owned == -1)
            return result_value(BGP_STATUS_BUSY, 0u);
        if (owned == -2)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        request.server_if = wire->server_if;
        request.inst = wire->instance;
        request.num_handle = wire->num_handles;
        request.is_primary = wire->is_primary ? TRUE : FALSE;
        status = shim.api.create_service(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        finish_failed_structural_call(type, status);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    case BGP_REQ_ADD_ATTRIBUTE: {
        const struct bgp_add_attribute_request *wire;
        tBSA_BLE_SE_ADDCHAR request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_add_attribute_request *)payload;
        if (wire->reserved != 0u || wire->is_descriptor > 1u)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        memset(&request, 0, sizeof(request));
        if (uuid_to_bsa(&wire->attribute_uuid, &request.char_uuid) != 0)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        operation_epoch = shim.epoch;
        owned = service_index_locked(wire->service_id) >= 0;
        if (owned && !extension_operational_locked())
            owned = -2;
        else if (owned && begin_structural_locked(type) != 0)
            owned = -1;
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == 0)
            return result_value(BGP_STATUS_NOT_OWNER, 0u);
        if (owned == -1)
            return result_value(BGP_STATUS_BUSY, 0u);
        if (owned == -2)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        request.is_descr = wire->is_descriptor ? TRUE : FALSE;
        request.service_id = wire->service_id;
        request.perm = wire->permissions;
        request.property = wire->properties;
        status = shim.api.add_attribute(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        finish_failed_structural_call(type, status);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    case BGP_REQ_START_SERVICE: {
        const struct bgp_start_service_request *wire;
        tBSA_BLE_SE_START request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_start_service_request *)payload;
        if (wire->reserved != 0u ||
            (wire->transport != BSA_BLE_GATT_TRANSPORT_BR_EDR &&
             wire->transport != BSA_BLE_GATT_TRANSPORT_LE &&
             wire->transport != BSA_BLE_GATT_TRANSPORT_LE_BR_EDR))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        operation_epoch = shim.epoch;
        owned = service_index_locked(wire->service_id) >= 0;
        if (owned && !extension_operational_locked())
            owned = -2;
        else if (owned && begin_structural_locked(type) != 0)
            owned = -1;
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == 0)
            return result_value(BGP_STATUS_NOT_OWNER, 0u);
        if (owned == -1)
            return result_value(BGP_STATUS_BUSY, 0u);
        if (owned == -2)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        memset(&request, 0, sizeof(request));
        request.service_id = wire->service_id;
        request.sup_transport = wire->transport;
        status = shim.api.start_service(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        finish_failed_structural_call(type, status);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    case BGP_REQ_STOP_SERVICE: {
        const struct bgp_stop_service_request *wire;
        tBSA_BLE_SE_STOP request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_stop_service_request *)payload;
        if (wire->reserved != 0u)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        operation_epoch = shim.epoch;
        owned = service_index_locked(wire->service_id) >= 0;
        if (owned && !extension_operational_locked())
            owned = -2;
        else if (owned && begin_structural_locked(type) != 0)
            owned = -1;
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == 0)
            return result_value(BGP_STATUS_NOT_OWNER, 0u);
        if (owned == -1)
            return result_value(BGP_STATUS_BUSY, 0u);
        if (owned == -2)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        memset(&request, 0, sizeof(request));
        request.service_id = wire->service_id;
        status = shim.api.stop_service(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        finish_failed_structural_call(type, status);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    case BGP_REQ_SEND_RESPONSE: {
        const struct bgp_send_response_request *wire;
        struct pending_att pending;
        tBSA_BLE_SE_SENDRSP request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_send_response_request *)payload;
        if (wire->value_len > BGP_MAX_VALUE)
            return result_value(BGP_STATUS_TOO_LARGE, 0u);
        if (wire->auth_req > BSA_BLE_GATT_AUTH_REQ_SIGNED_MITM)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        if (!extension_operational_locked()) {
            owned = -1;
        } else {
            owned = take_pending_locked(wire->cookie, &pending) == 0;
            if (owned)
                operation_epoch = pending.epoch;
        }
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == -1)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        if (!owned)
            return result_value(BGP_STATUS_STALE, 0u);
        memset(&request, 0, sizeof(request));
        request.conn_id = pending.conn_id;
        request.trans_id = (uint16_t)pending.trans_id;
        request.status = wire->att_status;
        request.handle = pending.handle;
        request.offset = pending.offset;
        request.len = wire->value_len;
        request.auth_req = wire->auth_req;
        if (wire->value_len != 0u)
            memcpy(request.value, wire->value, wire->value_len);
        status = shim.api.send_response(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    case BGP_REQ_SEND_INDICATION: {
        const struct bgp_send_indication_request *wire;
        tBSA_BLE_SE_SENDIND request;

        if (length != sizeof(*wire))
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        wire = (const struct bgp_send_indication_request *)payload;
        if (wire->value_len > BGP_MAX_VALUE)
            return result_value(BGP_STATUS_TOO_LARGE, 0u);
        if (wire->reserved != 0u || wire->need_confirm > 1u)
            return result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        (void)pthread_mutex_lock(&shim.mutex);
        operation_epoch = shim.epoch;
        if (!extension_operational_locked()) {
            owned = -1;
        } else {
            owned = attribute_owned_locked(wire->attr_id) &&
                    connection_owned_locked(wire->conn_id);
        }
        (void)pthread_mutex_unlock(&shim.mutex);
        if (owned == -1)
            return result_value(BGP_STATUS_NOT_READY, 0u);
        if (!owned)
            return result_value(BGP_STATUS_NOT_OWNER, 0u);
        memset(&request, 0, sizeof(request));
        request.conn_id = wire->conn_id;
        request.attr_id = wire->attr_id;
        request.data_len = wire->value_len;
        request.need_confirm = wire->need_confirm ? TRUE : FALSE;
        if (wire->value_len != 0u)
            memcpy(request.value, wire->value, wire->value_len);
        status = shim.api.send_indication(&request);
        if (!operation_epoch_current(operation_epoch))
            return result_value(BGP_STATUS_NOT_READY, 0u);
        return result_value(status == BSA_SUCCESS ? BGP_STATUS_OK
                                                  : BGP_STATUS_BSA,
                            status);
    }
    default:
        return result_value(BGP_STATUS_UNSUPPORTED, 0u);
    }
}

static int handle_frame(int fd, const struct bgp_frame_header *header,
                        const uint8_t *payload, int *hello_complete)
{
    if (header->request_id == 0u ||
        (header->type & UINT16_C(0xc000)) != 0u)
        return -1;

    if (header->type == BGP_REQ_HELLO) {
        struct bgp_hello_response response;

        if (header->payload_len != sizeof(struct bgp_hello_request))
            return -1;
        memset(&response, 0, sizeof(response));
        response.result = result_value(BGP_STATUS_OK, BSA_SUCCESS);
        response.shim_capabilities = BGP_CAP_GATT_SERVER |
                                     BGP_CAP_READ_WRITE |
                                     BGP_CAP_NOTIFY_INDICATE;
        (void)pthread_mutex_lock(&shim.mutex);
        response.state_flags = state_flags_locked();
        response.epoch = shim.epoch;
        (void)pthread_mutex_unlock(&shim.mutex);
        *hello_complete = 1;
        return send_frame(fd, BGP_RESPONSE_TYPE(header->type),
                          header->request_id, &response, sizeof(response));
    }
    if (!*hello_complete)
        return -1;

    if (header->type == BGP_REQ_REGISTER) {
        const struct bgp_register_request *wire;
        struct bgp_register_response response;
        tBSA_BLE_SE_REGISTER request;
        tBSA_STATUS status;
        int ready;
        uint32_t operation_epoch = 0u;

        (void)pthread_mutex_lock(&shim.lifecycle_mutex);
        memset(&response, 0, sizeof(response));
        if (header->payload_len != sizeof(*wire)) {
            response.result = result_value(BGP_STATUS_BAD_ARGUMENT, 0u);
        } else {
            wire = (const struct bgp_register_request *)payload;
            (void)pthread_mutex_lock(&shim.mutex);
            operation_epoch = shim.epoch;
            ready = ready_for_extension_locked();
            if (shim.extension_registered)
                ready = -1;
            (void)pthread_mutex_unlock(&shim.mutex);
            if (ready == 0) {
                response.result = result_value(BGP_STATUS_NOT_READY, 0u);
            } else if (ready < 0) {
                response.result = result_value(BGP_STATUS_BUSY, 0u);
            } else {
                memset(&request, 0, sizeof(request));
                if (uuid_to_bsa(&wire->application_uuid, &request.uuid) != 0) {
                    response.result = result_value(BGP_STATUS_BAD_ARGUMENT,
                                                   0u);
                } else {
                    request.server_if = BSA_BLE_INVALID_IF;
                    request.p_cback = bsa_ble_mux;
                    status = shim.api.app_register(&request);
                    response.result = result_value(
                        status == BSA_SUCCESS ? BGP_STATUS_OK : BGP_STATUS_BSA,
                        status);
                    if (status == BSA_SUCCESS) {
                        (void)pthread_mutex_lock(&shim.mutex);
                        if (shim.epoch == operation_epoch &&
                            shim.client_fd == fd) {
                            shim.extension_registered = 1u;
                            shim.extension_server_if = request.server_if;
                            response.server_if = request.server_if;
                            response.epoch = shim.epoch;
                            queue_state_locked(BGP_STATUS_OK, status);
                        } else {
                            response.result = result_value(
                                BGP_STATUS_NOT_READY, status);
                        }
                        (void)pthread_mutex_unlock(&shim.mutex);
                    }
                }
            }
        }
        (void)pthread_mutex_unlock(&shim.lifecycle_mutex);
        return send_frame(fd, BGP_RESPONSE_TYPE(header->type),
                          header->request_id, &response, sizeof(response));
    }

    {
        struct bgp_result result;

        (void)pthread_mutex_lock(&shim.lifecycle_mutex);
        result = handle_generic_command(header->type, payload,
                                        header->payload_len);
        (void)pthread_mutex_unlock(&shim.lifecycle_mutex);
        return send_frame(fd, BGP_RESPONSE_TYPE(header->type),
                          header->request_id, &result, sizeof(result));
    }
}

static int create_listener(char *bound_path, size_t bound_path_size)
{
    struct sockaddr_un address;
    struct stat path_status;
    const char *configured = getenv(BGP_SOCKET_ENV);
    const char *path = configured != NULL && configured[0] != '\0'
                           ? configured : BGP_DEFAULT_SOCKET;
    size_t path_length = strlen(path);
    int fd;
    int flags;
    int probe;
    int connect_error;
    socklen_t address_length;

    if (path_length == 0u || path_length >= sizeof(address.sun_path) ||
        path_length >= bound_path_size)
        return -1;
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0)
        return -1;
    flags = fcntl(fd, F_GETFD);
    if (flags >= 0)
        (void)fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, path, path_length + 1u);
    address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) +
                                 path_length + 1u);
    if (lstat(path, &path_status) == 0) {
        if (!S_ISSOCK(path_status.st_mode)) {
            (void)close(fd);
            return -1;
        }
        probe = socket(AF_UNIX, SOCK_STREAM, 0);
        if (probe < 0) {
            (void)close(fd);
            return -1;
        }
        if (connect(probe, (struct sockaddr *)&address, address_length) == 0) {
            (void)close(probe);
            (void)close(fd);
            return -1;
        }
        connect_error = errno;
        (void)close(probe);
        if (connect_error != ECONNREFUSED && connect_error != ENOENT) {
            (void)close(fd);
            return -1;
        }
        if (unlink(path) != 0) {
            (void)close(fd);
            return -1;
        }
    } else if (errno != ENOENT) {
        (void)close(fd);
        return -1;
    }
    if (bind(fd, (struct sockaddr *)&address, address_length) != 0 ||
        chmod(path, S_IRUSR | S_IWUSR) != 0 || listen(fd, 1) != 0) {
        (void)close(fd);
        (void)unlink(path);
        return -1;
    }
    memcpy(bound_path, path, path_length + 1u);
    return fd;
}

static int accept_same_uid(int listener)
{
    struct ucred credentials;
    socklen_t credentials_length = sizeof(credentials);
    int fd = accept(listener, NULL, NULL);
    int flags;

    if (fd < 0)
        return -1;
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials,
                   &credentials_length) != 0 || credentials.uid != geteuid()) {
        (void)close(fd);
        return -1;
    }
    flags = fcntl(fd, F_GETFD);
    if (flags >= 0)
        (void)fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
    return fd;
}

static void fail_all_pending(void)
{
    struct pending_att pending[PENDING_ATT_CAPACITY];
    unsigned int count = 0u;
    unsigned int index;

    (void)pthread_mutex_lock(&shim.mutex);
    for (index = 0u; index < PENDING_ATT_CAPACITY; index++) {
        if (shim.pending[index].used)
            pending[count++] = shim.pending[index];
    }
    memset(shim.pending, 0, sizeof(shim.pending));
    (void)pthread_mutex_unlock(&shim.mutex);
    for (index = 0u; index < count; index++) {
        send_att_error(pending[index].conn_id, pending[index].trans_id,
                       pending[index].handle, pending[index].offset,
                       BSA_GATT_ERR_UNLIKELY);
    }
}

static struct timespec cleanup_deadline(void)
{
    struct timespec deadline;
    struct timeval now;

    (void)gettimeofday(&now, NULL);
    deadline.tv_sec = now.tv_sec;
    deadline.tv_nsec = now.tv_usec * 1000L;
    deadline.tv_sec += CLEANUP_TIMEOUT_MS / 1000L;
    deadline.tv_nsec += (CLEANUP_TIMEOUT_MS % 1000L) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) {
        deadline.tv_sec++;
        deadline.tv_nsec -= 1000000000L;
    }
    return deadline;
}

static int wait_structural_idle_locked(const struct timespec *deadline)
{
    int status = 0;

    while (shim.structural_pending != 0u && status == 0)
        status = pthread_cond_timedwait(&shim.callback_cond, &shim.mutex,
                                        deadline);
    return shim.structural_pending == 0u ? 0 : -1;
}

static int cleanup_stop_service(uint16_t service_id,
                                const struct timespec *deadline,
                                uint32_t cleanup_epoch)
{
    tBSA_BLE_SE_STOP stop;
    tBSA_STATUS status;
    int index;
    int completed;

    (void)pthread_mutex_lock(&shim.mutex);
    if (wait_structural_idle_locked(deadline) != 0 ||
        shim.epoch != cleanup_epoch || !extension_operational_locked()) {
        (void)pthread_mutex_unlock(&shim.mutex);
        return -1;
    }
    index = service_index_locked(service_id);
    if (index < 0 || !shim.services[index].started) {
        (void)pthread_mutex_unlock(&shim.mutex);
        return 0;
    }
    if (begin_structural_locked(BGP_REQ_STOP_SERVICE) != 0) {
        (void)pthread_mutex_unlock(&shim.mutex);
        return -1;
    }
    (void)pthread_mutex_unlock(&shim.mutex);

    memset(&stop, 0, sizeof(stop));
    stop.service_id = service_id;
    status = shim.api.stop_service(&stop);
    if (!operation_epoch_current(cleanup_epoch))
        return -1;
    finish_failed_structural_call(BGP_REQ_STOP_SERVICE, status);
    if (status != BSA_SUCCESS)
        return -1;

    (void)pthread_mutex_lock(&shim.mutex);
    completed = shim.epoch == cleanup_epoch &&
                wait_structural_idle_locked(deadline) == 0;
    index = service_index_locked(service_id);
    if (index >= 0 && shim.services[index].started)
        completed = 0;
    (void)pthread_mutex_unlock(&shim.mutex);
    return completed ? 0 : -1;
}

static int cleanup_deregister(uint8_t server_if,
                              const struct timespec *deadline,
                              uint32_t cleanup_epoch)
{
    tBSA_BLE_SE_DEREGISTER deregister;
    tBSA_STATUS status;
    int completed;

    (void)pthread_mutex_lock(&shim.mutex);
    if (wait_structural_idle_locked(deadline) != 0 ||
        shim.epoch != cleanup_epoch || !extension_operational_locked() ||
        shim.extension_server_if != server_if ||
        any_service_started_locked() ||
        begin_structural_locked(BGP_REQ_DEREGISTER) != 0) {
        (void)pthread_mutex_unlock(&shim.mutex);
        return -1;
    }
    (void)pthread_mutex_unlock(&shim.mutex);

    memset(&deregister, 0, sizeof(deregister));
    deregister.server_if = server_if;
    status = shim.api.app_deregister(&deregister);
    if (!operation_epoch_current(cleanup_epoch))
        return -1;
    finish_failed_structural_call(BGP_REQ_DEREGISTER, status);
    if (status != BSA_SUCCESS)
        return -1;

    (void)pthread_mutex_lock(&shim.mutex);
    completed = shim.epoch == cleanup_epoch &&
                wait_structural_idle_locked(deadline) == 0 &&
                !shim.extension_registered;
    (void)pthread_mutex_unlock(&shim.mutex);
    return completed ? 0 : -1;
}

static void cleanup_extension_lease(void)
{
    uint16_t services[SERVICE_CAPACITY];
    struct timespec deadline;
    unsigned int service_count = 0u;
    unsigned int index;
    uint8_t server_if = BSA_BLE_INVALID_IF;
    int may_call_bsa;
    uint32_t cleanup_epoch;

    /* Keep stock facade transitions out of the teardown transaction. */
    (void)pthread_mutex_lock(&shim.lifecycle_mutex);
    fail_all_pending();
    (void)pthread_mutex_lock(&shim.mutex);
    shim.cleanup_in_progress = 1u;
    deadline = cleanup_deadline();
    cleanup_epoch = shim.epoch;
    if (wait_structural_idle_locked(&deadline) != 0) {
        may_call_bsa = 0;
        goto cleanup_snapshot_done;
    }
    may_call_bsa = extension_operational_locked();
    if (may_call_bsa) {
        server_if = shim.extension_server_if;
        for (index = 0u; index < SERVICE_CAPACITY; index++) {
            if (shim.services[index].used && shim.services[index].started)
                services[service_count++] = shim.services[index].id;
        }
    }
cleanup_snapshot_done:
    (void)pthread_mutex_unlock(&shim.mutex);

    if (may_call_bsa && resolve_symbols()) {
        for (index = 0u; index < service_count; index++) {
            if (cleanup_stop_service(services[index], &deadline,
                                     cleanup_epoch) != 0)
                break;
        }
        if (index == service_count)
            (void)cleanup_deregister(server_if, &deadline, cleanup_epoch);
    }

    /*
     * Keep the ownership maps until the asynchronous DEREGISTER event.  If
     * deregistration fails or its completion never arrives, this deliberately
     * quarantines the slot until the next BLE epoch.  Clearing the maps here
     * would misroute late extension callbacks into blebridge's stock callback.
     */
    (void)pthread_mutex_lock(&shim.mutex);
    if (!may_call_bsa && (!shim.mgt_open || !shim.ble_enabled))
        clear_extension_locked();
    shim.cleanup_in_progress = 0u;
    queue_state_locked(BGP_STATUS_OK, BSA_SUCCESS);
    (void)pthread_mutex_unlock(&shim.mutex);
    (void)pthread_mutex_unlock(&shim.lifecycle_mutex);
}

static int send_queued_frames(int fd)
{
    for (;;) {
        struct queued_frame frame;

        (void)pthread_mutex_lock(&shim.mutex);
        if (shim.queue_count == 0u) {
            (void)pthread_mutex_unlock(&shim.mutex);
            return 0;
        }
        frame = shim.queue[shim.queue_head];
        shim.queue_head = (shim.queue_head + 1u) % EVENT_QUEUE_CAPACITY;
        shim.queue_count--;
        (void)pthread_mutex_unlock(&shim.mutex);
        if (send_frame(fd, frame.type, frame.request_id,
                       frame.payload, frame.length) != 0)
            return -1;
    }
}

static void drain_wake_pipe(void)
{
    uint8_t bytes[32];

    while (shim.wake_pipe[0] >= 0 &&
           read(shim.wake_pipe[0], bytes, sizeof(bytes)) > 0) {
    }
}

static void disconnect_owner(int fd)
{
    int was_owner = 0;

    (void)pthread_mutex_lock(&shim.mutex);
    if (shim.client_fd == fd) {
        shim.client_fd = -1;
        shim.queue_head = 0u;
        shim.queue_count = 0u;
        was_owner = 1;
    }
    (void)pthread_mutex_unlock(&shim.mutex);
    (void)close(fd);
    if (was_owner)
        cleanup_extension_lease();
}

static void worker_stopped(void)
{
    int read_fd;
    int write_fd;

    (void)pthread_mutex_lock(&shim.mutex);
    read_fd = shim.wake_pipe[0];
    write_fd = shim.wake_pipe[1];
    shim.wake_pipe[0] = -1;
    shim.wake_pipe[1] = -1;
    shim.worker_started = 0;
    (void)pthread_mutex_unlock(&shim.mutex);
    if (read_fd >= 0)
        (void)close(read_fd);
    if (write_fd >= 0)
        (void)close(write_fd);
}

static void *broker_worker(void *unused)
{
    char bound_path[sizeof(((struct sockaddr_un *)0)->sun_path)];
    int listener;
    int client = -1;
    int hello_complete = 0;

    (void)unused;
    memset(bound_path, 0, sizeof(bound_path));
    listener = create_listener(bound_path, sizeof(bound_path));
    if (listener < 0) {
        worker_stopped();
        return NULL;
    }

    for (;;) {
        struct pollfd descriptors[3];
        nfds_t count = 2u;
        int result;

        descriptors[0].fd = listener;
        (void)pthread_mutex_lock(&shim.mutex);
        descriptors[0].events = client < 0 && !shim.cleanup_in_progress &&
                                        !shim.extension_registered
                                    ? POLLIN : 0;
        (void)pthread_mutex_unlock(&shim.mutex);
        descriptors[0].revents = 0;
        descriptors[1].fd = shim.wake_pipe[0];
        descriptors[1].events = POLLIN;
        descriptors[1].revents = 0;
        if (client >= 0) {
            descriptors[2].fd = client;
            descriptors[2].events = POLLIN;
            descriptors[2].revents = 0;
            count = 3u;
        }
        do {
            result = poll(descriptors, count, -1);
        } while (result < 0 && errno == EINTR);
        if (result < 0)
            break;

        if ((descriptors[1].revents & POLLIN) != 0) {
            drain_wake_pipe();
            if (client >= 0 && send_queued_frames(client) != 0) {
                disconnect_owner(client);
                client = -1;
                hello_complete = 0;
            }
        }
        if ((descriptors[0].revents & POLLIN) != 0 && client < 0) {
            int accepted = accept_same_uid(listener);

            if (accepted >= 0) {
                client = accepted;
                hello_complete = 0;
                (void)pthread_mutex_lock(&shim.mutex);
                shim.client_fd = client;
                queue_state_locked(BGP_STATUS_OK, BSA_SUCCESS);
                (void)pthread_mutex_unlock(&shim.mutex);
            }
        }
        if (client >= 0 && count == 3u && descriptors[2].revents != 0) {
            if ((descriptors[2].revents & POLLIN) != 0) {
                struct bgp_frame_header header;
                uint8_t payload[BGP_MAX_PAYLOAD];

                if (read_exact(client, &header, sizeof(header)) != 0 ||
                    header.magic != BGP_MAGIC ||
                    header.version != BGP_VERSION ||
                    header.payload_len > BGP_MAX_PAYLOAD ||
                    (header.payload_len != 0u &&
                     read_exact(client, payload, header.payload_len) != 0) ||
                    handle_frame(client, &header, payload,
                                 &hello_complete) != 0) {
                    disconnect_owner(client);
                    client = -1;
                    hello_complete = 0;
                }
            } else {
                disconnect_owner(client);
                client = -1;
                hello_complete = 0;
            }
        }
    }

    if (client >= 0)
        disconnect_owner(client);
    (void)close(listener);
    /* A restart removes a stale socket after verifying its type. */
    (void)bound_path;
    worker_stopped();
    return NULL;
}

static void start_worker(void)
{
    pthread_t thread;
    pthread_attr_t attributes;
    int flags;
    int should_start = 0;
    int create_status = -1;

    (void)pthread_mutex_lock(&shim.mutex);
    if (!shim.worker_started) {
        if (pipe(shim.wake_pipe) == 0) {
            flags = fcntl(shim.wake_pipe[0], F_GETFL);
            if (flags >= 0)
                (void)fcntl(shim.wake_pipe[0], F_SETFL, flags | O_NONBLOCK);
            flags = fcntl(shim.wake_pipe[1], F_GETFL);
            if (flags >= 0)
                (void)fcntl(shim.wake_pipe[1], F_SETFL, flags | O_NONBLOCK);
            flags = fcntl(shim.wake_pipe[0], F_GETFD);
            if (flags >= 0)
                (void)fcntl(shim.wake_pipe[0], F_SETFD, flags | FD_CLOEXEC);
            flags = fcntl(shim.wake_pipe[1], F_GETFD);
            if (flags >= 0)
                (void)fcntl(shim.wake_pipe[1], F_SETFD, flags | FD_CLOEXEC);
            shim.worker_started = 1;
            should_start = 1;
        }
    }
    (void)pthread_mutex_unlock(&shim.mutex);
    if (should_start) {
        if (pthread_attr_init(&attributes) == 0) {
            (void)pthread_attr_setdetachstate(&attributes,
                                              PTHREAD_CREATE_DETACHED);
            if (pthread_attr_setstacksize(&attributes, 64u * 1024u) == 0)
                create_status = pthread_create(&thread, &attributes,
                                               broker_worker, NULL);
            (void)pthread_attr_destroy(&attributes);
        }
        if (create_status != 0) {
            (void)pthread_mutex_lock(&shim.mutex);
            shim.worker_started = 0;
            (void)close(shim.wake_pipe[0]);
            (void)close(shim.wake_pipe[1]);
            shim.wake_pipe[0] = -1;
            shim.wake_pipe[1] = -1;
            (void)pthread_mutex_unlock(&shim.mutex);
        }
    }
}

static void bsa_mgt_mux(tBSA_MGT_EVT event, tBSA_MGT_MSG *message)
{
    tBSA_MGT_CBACK *callback;

    (void)pthread_mutex_lock(&shim.mutex);
    if (event == BSA_MGT_DISCONNECT_EVT) {
        /*
         * A status event with enable=false revokes the GATT facade but does
         * not close the management connection.  Keep a separate generation
         * for the only callback that can invalidate a successful MgtOpen.
         */
        shim.mgt_disconnect_generation++;
        shim.mgt_open = 0u;
        shim.ble_enabled = 0u;
        shim.stock_registered = 0u;
        shim.stock_started = 0u;
        shim.stock_server_if = BSA_BLE_INVALID_IF;
        invalidate_facade_locked(BGP_STATUS_BAD_STATE,
                                 BSA_ERROR_CLI_INTERNAL);
    } else if (event == BSA_MGT_STATUS_EVT && message != NULL) {
        shim.ble_enabled = message->status.enable ? 1u : 0u;
        if (!message->status.enable) {
            shim.stock_registered = 0u;
            shim.stock_started = 0u;
            shim.stock_server_if = BSA_BLE_INVALID_IF;
            invalidate_facade_locked(BGP_STATUS_NOT_READY,
                                     BSA_ERROR_SRV_BLUETOOTH_DISABLE);
        } else {
            queue_state_locked(BGP_STATUS_OK, BSA_SUCCESS);
        }
    }
    callback = shim.stock_mgt_callback;
    (void)pthread_mutex_unlock(&shim.mutex);
    if (callback != NULL && callback != bsa_mgt_mux)
        callback(event, message);
}

static void bsa_ble_mux(tBSA_BLE_EVT event, tBSA_BLE_MSG *message)
{
    tBSA_BLE_CBACK *stock_callback;
    int extension_event = 0;
    int also_stock = 0;
    int expected_queue = 0;
    int fatal_extension_state = 0;
    int queued = 0;
    struct pending_att close_pending[PENDING_ATT_CAPACITY];
    unsigned int close_pending_count = 0u;
    int send_error = 0;
    uint16_t error_conn = 0u;
    uint32_t error_trans = 0u;
    uint16_t error_handle = 0u;
    uint16_t error_offset = 0u;
    uint8_t error_status = BSA_GATT_ERR_UNLIKELY;

    (void)pthread_mutex_lock(&shim.mutex);
    stock_callback = shim.stock_ble_callback;
    if (message == NULL)
        goto callback_done;

    switch (event) {
    case BSA_BLE_SE_DEREGISTER_EVT:
        extension_event = shim.extension_registered &&
            message->ser_deregister.server_if == shim.extension_server_if;
        if (extension_event) {
            struct bgp_deregister_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.bsa_status = message->ser_deregister.status;
            wire.server_if = message->ser_deregister.server_if;
            queued = queue_frame_locked(BGP_EVT_DEREGISTER, 0u,
                                        &wire, sizeof(wire)) == 0;
            finish_structural_locked(BGP_REQ_DEREGISTER);
            if (wire.bsa_status == BSA_SUCCESS)
                clear_extension_locked();
            queue_state_locked(BGP_STATUS_OK, wire.bsa_status);
        } else if (message->ser_deregister.server_if ==
                   shim.stock_server_if) {
            if (message->ser_deregister.status == BSA_SUCCESS) {
                shim.stock_registered = 0u;
                shim.stock_started = 0u;
                shim.stock_server_if = BSA_BLE_INVALID_IF;
            }
            queue_state_locked(BGP_STATUS_OK,
                               message->ser_deregister.status);
        }
        break;
    case BSA_BLE_SE_CREATE_EVT:
        extension_event = shim.extension_registered &&
            message->ser_create.server_if == shim.extension_server_if;
        if (extension_event) {
            struct bgp_create_service_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.bsa_status = message->ser_create.status;
            wire.server_if = message->ser_create.server_if;
            wire.service_id = message->ser_create.service_id;
            if (wire.bsa_status == BSA_SUCCESS &&
                remember_service_locked(wire.service_id) != 0)
                fatal_extension_state = 1;
            queued = queue_frame_locked(BGP_EVT_CREATE_SERVICE, 0u,
                                        &wire, sizeof(wire)) == 0;
            finish_structural_locked(BGP_REQ_CREATE_SERVICE);
        }
        break;
    case BSA_BLE_SE_ADDCHAR_EVT:
        extension_event = shim.extension_registered &&
            message->ser_addchar.server_if == shim.extension_server_if;
        if (extension_event) {
            struct bgp_add_attribute_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.bsa_status = message->ser_addchar.status;
            wire.server_if = message->ser_addchar.server_if;
            wire.is_descriptor = message->ser_addchar.is_discr;
            wire.service_id = message->ser_addchar.service_id;
            wire.attr_id = message->ser_addchar.attr_id;
            if (wire.bsa_status == BSA_SUCCESS &&
                remember_attribute_locked(wire.attr_id) != 0)
                fatal_extension_state = 1;
            queued = queue_frame_locked(BGP_EVT_ADD_ATTRIBUTE, 0u,
                                        &wire, sizeof(wire)) == 0;
            finish_structural_locked(BGP_REQ_ADD_ATTRIBUTE);
        }
        break;
    case BSA_BLE_SE_START_EVT:
    case BSA_BLE_SE_STOP_EVT: {
        tBSA_BLE_IF server_if = event == BSA_BLE_SE_START_EVT
            ? message->ser_start.server_if : message->ser_stop.server_if;
        uint16_t service_id = event == BSA_BLE_SE_START_EVT
            ? message->ser_start.service_id : message->ser_stop.service_id;
        tBSA_STATUS status = event == BSA_BLE_SE_START_EVT
            ? message->ser_start.status : message->ser_stop.status;
        int service_index = service_index_locked(service_id);

        extension_event = shim.extension_registered &&
                          server_if == shim.extension_server_if;
        if (extension_event) {
            struct bgp_service_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.bsa_status = status;
            wire.server_if = server_if;
            wire.service_id = service_id;
            if (service_index < 0) {
                fatal_extension_state = 1;
            } else if (status == BSA_SUCCESS) {
                shim.services[service_index].started =
                    event == BSA_BLE_SE_START_EVT ? 1u : 0u;
            }
            queued = queue_frame_locked(
                event == BSA_BLE_SE_START_EVT ? BGP_EVT_START_SERVICE
                                              : BGP_EVT_STOP_SERVICE,
                0u, &wire, sizeof(wire)) == 0;
            finish_structural_locked(event == BSA_BLE_SE_START_EVT
                                         ? BGP_REQ_START_SERVICE
                                         : BGP_REQ_STOP_SERVICE);
        } else if (status == BSA_SUCCESS &&
                   server_if == shim.stock_server_if) {
            shim.stock_started = event == BSA_BLE_SE_START_EVT ? 1u : 0u;
            queue_state_locked(BGP_STATUS_OK, status);
        }
        break;
    }
    case BSA_BLE_SE_READ_EVT:
        extension_event = attribute_owned_locked(message->ser_read.handle);
        if (extension_event) {
            struct bgp_read_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.cookie = allocate_pending_locked(&message->ser_read, NULL);
            memcpy(wire.remote_bda, message->ser_read.remote_bda,
                   sizeof(wire.remote_bda));
            wire.conn_id = message->ser_read.conn_id;
            wire.handle = message->ser_read.handle;
            wire.offset = message->ser_read.offset;
            wire.bsa_status = message->ser_read.status;
            wire.is_long = message->ser_read.is_long;
            if (wire.cookie != 0u)
                queued = queue_frame_locked(BGP_EVT_READ, 0u,
                                            &wire, sizeof(wire)) == 0;
            if (!queued) {
                struct pending_att discarded;
                if (wire.cookie != 0u)
                    (void)take_pending_locked(wire.cookie, &discarded);
                send_error = 1;
                error_conn = message->ser_read.conn_id;
                error_trans = message->ser_read.trans_id;
                error_handle = message->ser_read.handle;
                error_offset = message->ser_read.offset;
            }
        }
        break;
    case BSA_BLE_SE_WRITE_EVT:
        extension_event = attribute_owned_locked(message->ser_write.handle);
        if (extension_event) {
            if (message->ser_write.is_prep) {
                if (message->ser_write.need_rsp) {
                    send_error = 1;
                    error_status = BSA_GATT_REQ_NOT_SUPPORTED;
                    error_conn = message->ser_write.conn_id;
                    error_trans = message->ser_write.trans_id;
                    error_handle = message->ser_write.handle;
                    error_offset = message->ser_write.offset;
                }
            } else {
                struct bgp_write_event wire;
                size_t value_length = message->ser_write.len;
                expected_queue = 1;
                memset(&wire, 0, sizeof(wire));
                if (value_length > BGP_MAX_VALUE)
                    value_length = BGP_MAX_VALUE;
                if (message->ser_write.need_rsp)
                    wire.cookie = allocate_pending_locked(
                        NULL, &message->ser_write);
                memcpy(wire.remote_bda, message->ser_write.remote_bda,
                       sizeof(wire.remote_bda));
                wire.conn_id = message->ser_write.conn_id;
                wire.handle = message->ser_write.handle;
                wire.offset = message->ser_write.offset;
                wire.value_len = (uint16_t)value_length;
                wire.bsa_status = message->ser_write.status;
                wire.need_response = message->ser_write.need_rsp;
                wire.is_prepare = FALSE;
                if (value_length != 0u)
                    memcpy(wire.value, message->ser_write.value, value_length);
                if (!message->ser_write.need_rsp || wire.cookie != 0u)
                    queued = queue_frame_locked(BGP_EVT_WRITE, 0u,
                                                &wire, sizeof(wire)) == 0;
                if (!queued && message->ser_write.need_rsp) {
                    struct pending_att discarded;
                    if (wire.cookie != 0u)
                        (void)take_pending_locked(wire.cookie, &discarded);
                    send_error = 1;
                    error_conn = message->ser_write.conn_id;
                    error_trans = message->ser_write.trans_id;
                    error_handle = message->ser_write.handle;
                    error_offset = message->ser_write.offset;
                }
            }
        }
        break;
    case BSA_BLE_SE_EXEC_WRITE_EVT:
        /* v1 cannot attribute EXEC_WRITE safely; preserve stock behavior. */
        also_stock = 1;
        break;
    case BSA_BLE_SE_OPEN_EVT:
    case BSA_BLE_SE_CLOSE_EVT: {
        const tBSA_BLE_SE_OPEN_MSG *source = event == BSA_BLE_SE_OPEN_EVT
            ? &message->ser_open : &message->ser_close;
        extension_event = shim.extension_registered &&
                          source->server_if == shim.extension_server_if;
        if (extension_event) {
            struct bgp_connection_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.server_if = source->server_if;
            memcpy(wire.remote_bda, source->remote_bda,
                   sizeof(wire.remote_bda));
            wire.conn_id = source->conn_id;
            wire.reason = source->reason;
            if (event == BSA_BLE_SE_OPEN_EVT) {
                if (remember_connection_locked(source->conn_id) != 0)
                    fatal_extension_state = 1;
            } else {
                close_pending_count = take_pending_for_connection_locked(
                    source->conn_id, close_pending);
                forget_connection_locked(source->conn_id);
            }
            queued = queue_frame_locked(
                event == BSA_BLE_SE_OPEN_EVT ? BGP_EVT_OPEN : BGP_EVT_CLOSE,
                0u, &wire, sizeof(wire)) == 0;
        }
        break;
    }
    case BSA_BLE_SE_MTU_EVT: {
        struct bgp_mtu_event wire;
        memset(&wire, 0, sizeof(wire));
        wire.conn_id = message->ser_mtu.conn_id;
        wire.mtu = message->ser_mtu.att_mtu;
        extension_event = connection_owned_locked(message->ser_mtu.conn_id);
        also_stock = 1;
        if (extension_event) {
            expected_queue = 1;
            queued = queue_frame_locked(BGP_EVT_MTU, 0u,
                                        &wire, sizeof(wire)) == 0;
        }
        break;
    }
    case BSA_BLE_SE_CONGEST_EVT: {
        struct bgp_congestion_event wire;
        memset(&wire, 0, sizeof(wire));
        wire.conn_id = message->ser_congest.conn_id;
        wire.congested = message->ser_congest.congested;
        extension_event = connection_owned_locked(
            message->ser_congest.conn_id);
        also_stock = 1;
        if (extension_event) {
            expected_queue = 1;
            queued = queue_frame_locked(BGP_EVT_CONGESTION, 0u,
                                        &wire, sizeof(wire)) == 0;
        }
        break;
    }
    case BSA_BLE_SE_CONFIRM_EVT:
        extension_event = attribute_owned_locked(message->ser_confirm.attr_id);
        if (extension_event) {
            struct bgp_confirm_event wire;
            expected_queue = 1;
            memset(&wire, 0, sizeof(wire));
            wire.conn_id = message->ser_confirm.conn_id;
            wire.bsa_status = message->ser_confirm.status;
            wire.attr_id = message->ser_confirm.attr_id;
            queued = queue_frame_locked(BGP_EVT_CONFIRM, 0u,
                                        &wire, sizeof(wire)) == 0;
        }
        break;
    default:
        break;
    }

callback_done:
    if (fatal_extension_state || (expected_queue && !queued)) {
        if (shim.client_fd >= 0) {
            /* Shutdown while the descriptor is still authoritative. */
            (void)shutdown(shim.client_fd, SHUT_RDWR);
        }
    }
    (void)pthread_mutex_unlock(&shim.mutex);
    if (send_error)
        send_att_error(error_conn, error_trans, error_handle, error_offset,
                       error_status);
    /* The link is already closed; discard its cookies without a BSA reply. */
    (void)close_pending;
    (void)close_pending_count;
    if ((!extension_event || also_stock) && stock_callback != NULL &&
        stock_callback != bsa_ble_mux)
        stock_callback(event, message);
}

tBSA_STATUS BSA_MgtOpen(tBSA_MGT_OPEN *request)
{
    tBSA_MGT_OPEN copy;
    tBSA_STATUS status;
    uint32_t disconnect_generation;
    int committed = 0;

    if (!resolve_symbols())
        return BSA_ERROR_CLI_INTERNAL;
    (void)begin_lifecycle_call();
    if (request == NULL) {
        status = shim.api.mgt_open(NULL);
        finish_nondestructive_lifecycle_call();
        return status;
    }
    copy = *request;
    (void)pthread_mutex_lock(&shim.mutex);
    if (request->callback != bsa_mgt_mux)
        shim.stock_mgt_callback = request->callback;
    copy.callback = bsa_mgt_mux;
    disconnect_generation = shim.mgt_disconnect_generation;
    (void)pthread_mutex_unlock(&shim.mutex);
    status = shim.api.mgt_open(&copy);
    if (status == BSA_SUCCESS) {
        (void)pthread_mutex_lock(&shim.mutex);
        if (shim.mgt_disconnect_generation == disconnect_generation) {
            shim.mgt_open = 1u;
            queue_state_locked(BGP_STATUS_OK, status);
            committed = 1;
        }
        (void)pthread_mutex_unlock(&shim.mutex);
    }
    finish_nondestructive_lifecycle_call();
    if (committed)
        start_worker();
    return status;
}

tBSA_STATUS BSA_BleEnable(tBSA_BLE_ENABLE *request)
{
    tBSA_STATUS status;
    uint32_t operation_epoch;

    if (!resolve_symbols())
        return BSA_ERROR_CLI_INTERNAL;
    operation_epoch = begin_lifecycle_call();
    status = shim.api.ble_enable(request);
    if (status == BSA_SUCCESS) {
        (void)pthread_mutex_lock(&shim.mutex);
        if (shim.epoch == operation_epoch) {
            shim.ble_enabled = 1u;
            queue_state_locked(BGP_STATUS_OK, status);
        }
        (void)pthread_mutex_unlock(&shim.mutex);
    }
    finish_nondestructive_lifecycle_call();
    return status;
}

tBSA_STATUS BSA_BleSeAppRegister(tBSA_BLE_SE_REGISTER *request)
{
    tBSA_BLE_SE_REGISTER copy;
    tBSA_STATUS status;
    uint32_t operation_epoch;

    if (!resolve_symbols())
        return BSA_ERROR_CLI_INTERNAL;
    operation_epoch = begin_lifecycle_call();
    if (request == NULL) {
        status = shim.api.app_register(NULL);
        finish_nondestructive_lifecycle_call();
        return status;
    }
    copy = *request;
    (void)pthread_mutex_lock(&shim.mutex);
    if (request->p_cback != bsa_ble_mux)
        shim.stock_ble_callback = request->p_cback;
    copy.p_cback = bsa_ble_mux;
    (void)pthread_mutex_unlock(&shim.mutex);
    status = shim.api.app_register(&copy);
    request->server_if = copy.server_if;
    if (status == BSA_SUCCESS) {
        (void)pthread_mutex_lock(&shim.mutex);
        if (shim.epoch == operation_epoch) {
            shim.stock_registered = 1u;
            shim.stock_server_if = copy.server_if;
            queue_state_locked(BGP_STATUS_OK, status);
        }
        (void)pthread_mutex_unlock(&shim.mutex);
    }
    finish_nondestructive_lifecycle_call();
    return status;
}

tBSA_STATUS BSA_BleSeAppDeregister(tBSA_BLE_SE_DEREGISTER *request)
{
    tBSA_STATUS status;

    if (!resolve_symbols())
        return BSA_ERROR_CLI_INTERNAL;
    (void)begin_lifecycle_call();
    status = shim.api.app_deregister(request);
    finish_nondestructive_lifecycle_call();
    return status;
}

tBSA_STATUS BSA_BleDisable(tBSA_BLE_DISABLE *request)
{
    tBSA_STATUS status;
    uint32_t operation_epoch;

    if (!resolve_symbols())
        return BSA_ERROR_CLI_INTERNAL;
    operation_epoch = begin_lifecycle_call();
    status = shim.api.ble_disable(request);
    finish_lifecycle_call(operation_epoch, status, 0);
    return status;
}

tBSA_STATUS BSA_MgtClose(tBSA_MGT_CLOSE *request)
{
    tBSA_STATUS status;
    uint32_t operation_epoch;

    if (!resolve_symbols())
        return BSA_ERROR_CLI_INTERNAL;
    operation_epoch = begin_lifecycle_call();
    status = shim.api.mgt_close(request);
    finish_lifecycle_call(operation_epoch, status, 1);
    return status;
}
