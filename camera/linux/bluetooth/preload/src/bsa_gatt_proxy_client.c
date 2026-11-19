#include "bsa_gatt_proxy_client.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define BGP_CLIENT_EVENT_QUEUE_CAPACITY 16u
#define BGP_CLIENT_DEFAULT_TIMEOUT_MS 5000

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

struct bsa_gatt_proxy_client {
    int fd;
    int last_errno;
    int command_timeout_ms;
    int hello_complete;
    uint32_t next_request_id;
    unsigned int event_head;
    unsigned int event_count;
    struct bsa_gatt_proxy_event events[BGP_CLIENT_EVENT_QUEUE_CAPACITY];
};

static int client_connected(const struct bsa_gatt_proxy_client *client)
{
    return client != NULL && client->fd >= 0;
}

static void reset_session(struct bsa_gatt_proxy_client *client)
{
    client->hello_complete = 0;
    client->next_request_id = UINT32_C(1);
    client->event_head = 0u;
    client->event_count = 0u;
}

struct bsa_gatt_proxy_client *bsa_gatt_proxy_client_new(void)
{
    struct bsa_gatt_proxy_client *client = calloc(1u, sizeof(*client));

    if (client == NULL)
        return NULL;
    client->fd = -1;
    client->command_timeout_ms = BGP_CLIENT_DEFAULT_TIMEOUT_MS;
    client->next_request_id = UINT32_C(1);
    return client;
}

void bsa_gatt_proxy_disconnect(struct bsa_gatt_proxy_client *client)
{
    if (client == NULL)
        return;
    if (client->fd >= 0)
        (void)close(client->fd);
    client->fd = -1;
    reset_session(client);
}

void bsa_gatt_proxy_client_free(struct bsa_gatt_proxy_client *client)
{
    if (client == NULL)
        return;
    bsa_gatt_proxy_disconnect(client);
    free(client);
}

int bsa_gatt_proxy_is_connected(const struct bsa_gatt_proxy_client *client)
{
    return client_connected(client);
}

int bsa_gatt_proxy_fd(const struct bsa_gatt_proxy_client *client)
{
    return client == NULL ? -1 : client->fd;
}

int bsa_gatt_proxy_last_errno(const struct bsa_gatt_proxy_client *client)
{
    return client == NULL ? 0 : client->last_errno;
}

int bsa_gatt_proxy_set_command_timeout(
    struct bsa_gatt_proxy_client *client, int timeout_ms)
{
    if (client == NULL || timeout_ms < -1)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    client->command_timeout_ms = timeout_ms;
    return BSA_GATT_PROXY_CLIENT_OK;
}

int bsa_gatt_proxy_connect(struct bsa_gatt_proxy_client *client,
                           const char *socket_path)
{
    struct sockaddr_un address;
    socklen_t address_len;
    const char *environment_path;
    size_t path_len;
    int fd;
    int flags;

    if (client == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    if (client->fd >= 0)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;

    if (socket_path == NULL || socket_path[0] == '\0') {
        environment_path = getenv(BGP_SOCKET_ENV);
        socket_path = environment_path != NULL && environment_path[0] != '\0'
                          ? environment_path
                          : BGP_DEFAULT_SOCKET;
    }
    path_len = strlen(socket_path);
    if (path_len == 0u || path_len >= sizeof(address.sun_path))
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        client->last_errno = errno;
        return BSA_GATT_PROXY_CLIENT_IO;
    }
    flags = fcntl(fd, F_GETFD);
    if (flags >= 0)
        (void)fcntl(fd, F_SETFD, flags | FD_CLOEXEC);

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, socket_path, path_len + 1u);
    address_len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) +
                              path_len + 1u);
    if (connect(fd, (const struct sockaddr *)&address, address_len) != 0) {
        client->last_errno = errno;
        (void)close(fd);
        return BSA_GATT_PROXY_CLIENT_IO;
    }

    client->fd = fd;
    client->last_errno = 0;
    reset_session(client);
    return BSA_GATT_PROXY_CLIENT_OK;
}

void bsa_gatt_proxy_uuid16(struct bgp_uuid *uuid, uint16_t value)
{
    if (uuid == NULL)
        return;
    memset(uuid, 0, sizeof(*uuid));
    uuid->len = UINT16_C(2);
    uuid->value[0] = (uint8_t)(value >> 8);
    uuid->value[1] = (uint8_t)value;
}

void bsa_gatt_proxy_uuid32(struct bgp_uuid *uuid, uint32_t value)
{
    if (uuid == NULL)
        return;
    memset(uuid, 0, sizeof(*uuid));
    uuid->len = UINT16_C(4);
    uuid->value[0] = (uint8_t)(value >> 24);
    uuid->value[1] = (uint8_t)(value >> 16);
    uuid->value[2] = (uint8_t)(value >> 8);
    uuid->value[3] = (uint8_t)value;
}

int bsa_gatt_proxy_uuid128(struct bgp_uuid *uuid,
                           const uint8_t canonical[16])
{
    if (uuid == NULL || canonical == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(uuid, 0, sizeof(*uuid));
    uuid->len = UINT16_C(16);
    memcpy(uuid->value, canonical, 16u);
    return BSA_GATT_PROXY_CLIENT_OK;
}

static int wait_readable(struct bsa_gatt_proxy_client *client, int timeout_ms)
{
    struct pollfd descriptor;
    int result;

    descriptor.fd = client->fd;
    descriptor.events = POLLIN;
    descriptor.revents = 0;
    do {
        result = poll(&descriptor, 1u, timeout_ms);
    } while (result < 0 && errno == EINTR);
    if (result == 0)
        return BSA_GATT_PROXY_CLIENT_TIMEOUT;
    if (result < 0) {
        client->last_errno = errno;
        return BSA_GATT_PROXY_CLIENT_IO;
    }
    if ((descriptor.revents & POLLNVAL) != 0) {
        client->last_errno = EBADF;
        return BSA_GATT_PROXY_CLIENT_IO;
    }
    return BSA_GATT_PROXY_CLIENT_OK;
}

static int read_exact(struct bsa_gatt_proxy_client *client, void *destination,
                      size_t length, int initial_timeout_ms,
                      int idle_timeout_allowed)
{
    uint8_t *bytes = destination;
    size_t offset = 0u;

    while (offset < length) {
        int timeout_ms = offset == 0u ? initial_timeout_ms
                                      : client->command_timeout_ms;
        int status = wait_readable(client, timeout_ms);
        ssize_t count;

        if (status != BSA_GATT_PROXY_CLIENT_OK) {
            if (status == BSA_GATT_PROXY_CLIENT_TIMEOUT &&
                (offset != 0u || !idle_timeout_allowed))
                return BSA_GATT_PROXY_CLIENT_PROTOCOL;
            return status;
        }
        do {
            count = recv(client->fd, bytes + offset, length - offset, 0);
        } while (count < 0 && errno == EINTR);
        if (count == 0)
            return BSA_GATT_PROXY_CLIENT_CLOSED;
        if (count < 0) {
            client->last_errno = errno;
            return BSA_GATT_PROXY_CLIENT_IO;
        }
        offset += (size_t)count;
    }
    return BSA_GATT_PROXY_CLIENT_OK;
}

static int write_exact(struct bsa_gatt_proxy_client *client,
                       const void *source, size_t length)
{
    const uint8_t *bytes = source;
    size_t offset = 0u;

    while (offset < length) {
        ssize_t count;

        do {
            count = send(client->fd, bytes + offset, length - offset,
                         MSG_NOSIGNAL);
        } while (count < 0 && errno == EINTR);
        if (count == 0)
            return BSA_GATT_PROXY_CLIENT_CLOSED;
        if (count < 0) {
            client->last_errno = errno;
            return BSA_GATT_PROXY_CLIENT_IO;
        }
        offset += (size_t)count;
    }
    return BSA_GATT_PROXY_CLIENT_OK;
}

static int read_frame(struct bsa_gatt_proxy_client *client,
                      struct bgp_frame_header *header, uint8_t *payload,
                      int timeout_ms)
{
    int status = read_exact(client, header, sizeof(*header), timeout_ms, 1);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (header->magic != BGP_MAGIC || header->version != BGP_VERSION ||
        header->payload_len > BGP_MAX_PAYLOAD)
        return BSA_GATT_PROXY_CLIENT_PROTOCOL;
    if (header->payload_len == 0u)
        return BSA_GATT_PROXY_CLIENT_OK;
    return read_exact(client, payload, header->payload_len,
                      client->command_timeout_ms, 0);
}

static size_t expected_event_size(uint16_t type)
{
    switch (type) {
    case BGP_EVT_STATE:
        return sizeof(struct bgp_state_event);
    case BGP_EVT_DEREGISTER:
        return sizeof(struct bgp_deregister_event);
    case BGP_EVT_CREATE_SERVICE:
        return sizeof(struct bgp_create_service_event);
    case BGP_EVT_ADD_ATTRIBUTE:
        return sizeof(struct bgp_add_attribute_event);
    case BGP_EVT_START_SERVICE:
    case BGP_EVT_STOP_SERVICE:
        return sizeof(struct bgp_service_event);
    case BGP_EVT_READ:
        return sizeof(struct bgp_read_event);
    case BGP_EVT_WRITE:
        return sizeof(struct bgp_write_event);
    case BGP_EVT_EXEC_WRITE:
        return sizeof(struct bgp_exec_write_event);
    case BGP_EVT_OPEN:
    case BGP_EVT_CLOSE:
        return sizeof(struct bgp_connection_event);
    case BGP_EVT_MTU:
        return sizeof(struct bgp_mtu_event);
    case BGP_EVT_CONGESTION:
        return sizeof(struct bgp_congestion_event);
    case BGP_EVT_CONFIRM:
        return sizeof(struct bgp_confirm_event);
    default:
        return SIZE_MAX;
    }
}

static int decode_event(const struct bgp_frame_header *header,
                        const uint8_t *payload,
                        struct bsa_gatt_proxy_event *event)
{
    size_t expected;

    if ((header->type & UINT16_C(0xc000)) != UINT16_C(0x4000) ||
        header->request_id != 0u)
        return BSA_GATT_PROXY_CLIENT_PROTOCOL;
    expected = expected_event_size(header->type);
    if (expected != SIZE_MAX && header->payload_len != expected)
        return BSA_GATT_PROXY_CLIENT_PROTOCOL;

    memset(event, 0, sizeof(*event));
    event->type = header->type;
    event->payload_len = header->payload_len;
    if (header->payload_len != 0u)
        memcpy(event->payload.raw, payload, header->payload_len);
    if (header->type == BGP_EVT_WRITE &&
        event->payload.write.value_len > BGP_MAX_VALUE)
        return BSA_GATT_PROXY_CLIENT_PROTOCOL;
    return BSA_GATT_PROXY_CLIENT_OK;
}

static int enqueue_event(struct bsa_gatt_proxy_client *client,
                         const struct bsa_gatt_proxy_event *event)
{
    unsigned int index;

    if (client->event_count == BGP_CLIENT_EVENT_QUEUE_CAPACITY)
        return BSA_GATT_PROXY_CLIENT_QUEUE_FULL;
    index = (client->event_head + client->event_count) %
            BGP_CLIENT_EVENT_QUEUE_CAPACITY;
    client->events[index] = *event;
    ++client->event_count;
    return BSA_GATT_PROXY_CLIENT_OK;
}

static int dequeue_event(struct bsa_gatt_proxy_client *client,
                         struct bsa_gatt_proxy_event *event)
{
    if (client->event_count == 0u)
        return 0;
    *event = client->events[client->event_head];
    client->event_head = (client->event_head + 1u) %
                         BGP_CLIENT_EVENT_QUEUE_CAPACITY;
    --client->event_count;
    return 1;
}

static int fail_session(struct bsa_gatt_proxy_client *client, int status)
{
    bsa_gatt_proxy_disconnect(client);
    return status;
}

static int transact(struct bsa_gatt_proxy_client *client, uint16_t type,
                    const void *request, uint32_t request_len, void *response,
                    uint32_t response_len)
{
    struct bgp_frame_header outgoing;
    uint32_t request_id;
    int status;

    if (!client_connected(client))
        return BSA_GATT_PROXY_CLIENT_NOT_CONNECTED;
    if ((request_len != 0u && request == NULL) || response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    if (request_len > BGP_MAX_PAYLOAD || response_len > BGP_MAX_PAYLOAD)
        return BSA_GATT_PROXY_CLIENT_TOO_LARGE;

    request_id = client->next_request_id++;
    if (request_id == 0u) {
        request_id = client->next_request_id++;
        if (request_id == 0u)
            request_id = UINT32_C(1);
    }
    outgoing.magic = BGP_MAGIC;
    outgoing.version = BGP_VERSION;
    outgoing.type = type;
    outgoing.request_id = request_id;
    outgoing.payload_len = request_len;
    status = write_exact(client, &outgoing, sizeof(outgoing));
    if (status == BSA_GATT_PROXY_CLIENT_OK && request_len != 0u)
        status = write_exact(client, request, request_len);
    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return fail_session(client, status);

    for (;;) {
        struct bgp_frame_header incoming;
        uint8_t payload[BGP_MAX_PAYLOAD];

        status = read_frame(client, &incoming, payload,
                            client->command_timeout_ms);
        if (status != BSA_GATT_PROXY_CLIENT_OK)
            return fail_session(client, status);
        if ((incoming.type & UINT16_C(0xc000)) == UINT16_C(0x4000)) {
            struct bsa_gatt_proxy_event event;

            status = decode_event(&incoming, payload, &event);
            if (status == BSA_GATT_PROXY_CLIENT_OK)
                status = enqueue_event(client, &event);
            if (status != BSA_GATT_PROXY_CLIENT_OK)
                return fail_session(client, status);
            continue;
        }
        if (incoming.type != BGP_RESPONSE_TYPE(type) ||
            incoming.request_id != request_id ||
            incoming.payload_len != response_len)
            return fail_session(client, BSA_GATT_PROXY_CLIENT_PROTOCOL);
        memcpy(response, payload, response_len);
        return BSA_GATT_PROXY_CLIENT_OK;
    }
}

static int require_hello(const struct bsa_gatt_proxy_client *client)
{
    if (!client_connected(client))
        return BSA_GATT_PROXY_CLIENT_NOT_CONNECTED;
    if (!client->hello_complete)
        return BSA_GATT_PROXY_CLIENT_HELLO_REQUIRED;
    return BSA_GATT_PROXY_CLIENT_OK;
}

int bsa_gatt_proxy_hello(struct bsa_gatt_proxy_client *client,
                         uint32_t client_capabilities,
                         struct bgp_hello_response *response)
{
    struct bgp_hello_request request;
    int status;

    if (client == NULL || response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    request.client_capabilities = client_capabilities;
    status = transact(client, BGP_REQ_HELLO, &request, sizeof(request),
                      response, sizeof(*response));
    if (status == BSA_GATT_PROXY_CLIENT_OK)
        client->hello_complete =
            response->result.proxy_status == BGP_STATUS_OK &&
            response->result.bsa_status == 0u;
    return status;
}

int bsa_gatt_proxy_register(struct bsa_gatt_proxy_client *client,
                            const struct bgp_uuid *application_uuid,
                            struct bgp_register_response *response)
{
    struct bgp_register_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (application_uuid == NULL || response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.application_uuid = *application_uuid;
    return transact(client, BGP_REQ_REGISTER, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_deregister(struct bsa_gatt_proxy_client *client,
                              uint8_t server_if,
                              struct bgp_result *response)
{
    struct bgp_deregister_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.server_if = server_if;
    return transact(client, BGP_REQ_DEREGISTER, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_create_service(struct bsa_gatt_proxy_client *client,
                                  uint8_t server_if,
                                  const struct bgp_uuid *service_uuid,
                                  uint8_t instance, int is_primary,
                                  uint16_t num_handles,
                                  struct bgp_result *response)
{
    struct bgp_create_service_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (service_uuid == NULL || response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.server_if = server_if;
    request.instance = instance;
    request.is_primary = is_primary != 0;
    request.num_handles = num_handles;
    request.service_uuid = *service_uuid;
    return transact(client, BGP_REQ_CREATE_SERVICE, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_add_attribute(struct bsa_gatt_proxy_client *client,
                                 uint16_t service_id,
                                 const struct bgp_uuid *attribute_uuid,
                                 int is_descriptor, uint8_t properties,
                                 uint16_t permissions,
                                 struct bgp_result *response)
{
    struct bgp_add_attribute_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (attribute_uuid == NULL || response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.service_id = service_id;
    request.is_descriptor = is_descriptor != 0;
    request.properties = properties;
    request.permissions = permissions;
    request.attribute_uuid = *attribute_uuid;
    return transact(client, BGP_REQ_ADD_ATTRIBUTE, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_start_service(struct bsa_gatt_proxy_client *client,
                                 uint16_t service_id, uint8_t transport,
                                 struct bgp_result *response)
{
    struct bgp_start_service_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.service_id = service_id;
    request.transport = transport;
    return transact(client, BGP_REQ_START_SERVICE, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_stop_service(struct bsa_gatt_proxy_client *client,
                                uint16_t service_id,
                                struct bgp_result *response)
{
    struct bgp_stop_service_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (response == NULL)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.service_id = service_id;
    return transact(client, BGP_REQ_STOP_SERVICE, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_send_response(struct bsa_gatt_proxy_client *client,
                                 uint32_t cookie, uint8_t att_status,
                                 uint8_t auth_req, const void *value,
                                 uint16_t value_len,
                                 struct bgp_result *response)
{
    struct bgp_send_response_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (response == NULL || value_len > BGP_MAX_VALUE ||
        (value_len != 0u && value == NULL))
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.cookie = cookie;
    request.att_status = att_status;
    request.auth_req = auth_req;
    request.value_len = value_len;
    if (value_len != 0u)
        memcpy(request.value, value, value_len);
    return transact(client, BGP_REQ_SEND_RESPONSE, &request, sizeof(request),
                    response, sizeof(*response));
}

int bsa_gatt_proxy_send_indication(struct bsa_gatt_proxy_client *client,
                                   uint16_t conn_id, uint16_t attr_id,
                                   const void *value, uint16_t value_len,
                                   int need_confirm,
                                   struct bgp_result *response)
{
    struct bgp_send_indication_request request;
    int status = require_hello(client);

    if (status != BSA_GATT_PROXY_CLIENT_OK)
        return status;
    if (response == NULL || value_len > BGP_MAX_VALUE ||
        (value_len != 0u && value == NULL))
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    memset(&request, 0, sizeof(request));
    request.conn_id = conn_id;
    request.attr_id = attr_id;
    request.value_len = value_len;
    request.need_confirm = need_confirm != 0;
    if (value_len != 0u)
        memcpy(request.value, value, value_len);
    return transact(client, BGP_REQ_SEND_INDICATION, &request,
                    sizeof(request), response, sizeof(*response));
}

int bsa_gatt_proxy_receive_event(struct bsa_gatt_proxy_client *client,
                                 struct bsa_gatt_proxy_event *event,
                                 int timeout_ms)
{
    int status;

    if (client == NULL || event == NULL || timeout_ms < -1)
        return BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT;
    if (!client_connected(client))
        return BSA_GATT_PROXY_CLIENT_NOT_CONNECTED;
    if (dequeue_event(client, event))
        return BSA_GATT_PROXY_CLIENT_OK;

    for (;;) {
        struct bgp_frame_header header;
        uint8_t payload[BGP_MAX_PAYLOAD];

        status = read_frame(client, &header, payload, timeout_ms);
        if (status == BSA_GATT_PROXY_CLIENT_TIMEOUT)
            return status;
        if (status != BSA_GATT_PROXY_CLIENT_OK)
            return fail_session(client, status);
        if ((header.type & UINT16_C(0xc000)) != UINT16_C(0x4000))
            return fail_session(client, BSA_GATT_PROXY_CLIENT_PROTOCOL);
        status = decode_event(&header, payload, event);
        if (status != BSA_GATT_PROXY_CLIENT_OK)
            return fail_session(client, status);
        return BSA_GATT_PROXY_CLIENT_OK;
    }
}

const char *bsa_gatt_proxy_client_status_string(int status)
{
    switch (status) {
    case BSA_GATT_PROXY_CLIENT_OK:
        return "success";
    case BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT:
        return "bad argument";
    case BSA_GATT_PROXY_CLIENT_NOT_CONNECTED:
        return "not connected";
    case BSA_GATT_PROXY_CLIENT_IO:
        return "I/O error";
    case BSA_GATT_PROXY_CLIENT_CLOSED:
        return "peer closed connection";
    case BSA_GATT_PROXY_CLIENT_PROTOCOL:
        return "protocol error";
    case BSA_GATT_PROXY_CLIENT_TIMEOUT:
        return "timeout";
    case BSA_GATT_PROXY_CLIENT_QUEUE_FULL:
        return "event queue full";
    case BSA_GATT_PROXY_CLIENT_HELLO_REQUIRED:
        return "HELLO required";
    case BSA_GATT_PROXY_CLIENT_TOO_LARGE:
        return "payload too large";
    default:
        return "unknown client status";
    }
}
