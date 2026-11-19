#ifndef OLYMPUS_BSA_GATT_PROXY_CLIENT_H
#define OLYMPUS_BSA_GATT_PROXY_CLIENT_H

/*
 * Standalone client SDK for the blebridge GATT proxy.
 *
 * This interface depends only on libc/POSIX sockets and the proxy wire
 * protocol.  It deliberately does not include or link against libbsa.
 * A client object is serialized: do not call it concurrently from multiple
 * threads.  Async events that arrive while a command is waiting for its
 * response are retained and returned by bsa_gatt_proxy_receive_event().
 */

#include "bsa_gatt_proxy_protocol.h"

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum bsa_gatt_proxy_client_status {
    BSA_GATT_PROXY_CLIENT_OK = 0,
    BSA_GATT_PROXY_CLIENT_BAD_ARGUMENT = -1000,
    BSA_GATT_PROXY_CLIENT_NOT_CONNECTED = -1001,
    BSA_GATT_PROXY_CLIENT_IO = -1002,
    BSA_GATT_PROXY_CLIENT_CLOSED = -1003,
    BSA_GATT_PROXY_CLIENT_PROTOCOL = -1004,
    BSA_GATT_PROXY_CLIENT_TIMEOUT = -1005,
    BSA_GATT_PROXY_CLIENT_QUEUE_FULL = -1006,
    BSA_GATT_PROXY_CLIENT_HELLO_REQUIRED = -1007,
    BSA_GATT_PROXY_CLIENT_TOO_LARGE = -1008
};

union bsa_gatt_proxy_event_payload {
    struct bgp_state_event state;
    struct bgp_deregister_event deregister;
    struct bgp_create_service_event create_service;
    struct bgp_add_attribute_event add_attribute;
    struct bgp_service_event service;
    struct bgp_read_event read;
    struct bgp_write_event write;
    struct bgp_exec_write_event exec_write;
    struct bgp_connection_event connection;
    struct bgp_mtu_event mtu;
    struct bgp_congestion_event congestion;
    struct bgp_confirm_event confirm;
    uint8_t raw[BGP_MAX_PAYLOAD];
};

struct bsa_gatt_proxy_event {
    uint16_t type;
    uint32_t payload_len;
    union bsa_gatt_proxy_event_payload payload;
};

struct bsa_gatt_proxy_client;

struct bsa_gatt_proxy_client *bsa_gatt_proxy_client_new(void);
void bsa_gatt_proxy_client_free(struct bsa_gatt_proxy_client *client);

/*
 * Connect without performing HELLO.  If socket_path is NULL or empty, the
 * BSA_GATT_PROXY_SOCKET environment variable is used when nonempty, followed
 * by BGP_DEFAULT_SOCKET.
 */
int bsa_gatt_proxy_connect(struct bsa_gatt_proxy_client *client,
                           const char *socket_path);
void bsa_gatt_proxy_disconnect(struct bsa_gatt_proxy_client *client);
int bsa_gatt_proxy_is_connected(const struct bsa_gatt_proxy_client *client);
int bsa_gatt_proxy_fd(const struct bsa_gatt_proxy_client *client);
int bsa_gatt_proxy_last_errno(const struct bsa_gatt_proxy_client *client);

/* timeout_ms is used by command/reply operations; -1 means no timeout. */
int bsa_gatt_proxy_set_command_timeout(
    struct bsa_gatt_proxy_client *client, int timeout_ms);

void bsa_gatt_proxy_uuid16(struct bgp_uuid *uuid, uint16_t value);
void bsa_gatt_proxy_uuid32(struct bgp_uuid *uuid, uint32_t value);
int bsa_gatt_proxy_uuid128(struct bgp_uuid *uuid,
                           const uint8_t canonical[16]);

/*
 * A return value describes local transport/protocol success.  On local
 * success, inspect response.result.proxy_status and bsa_status for the remote
 * operation result.
 */
int bsa_gatt_proxy_hello(struct bsa_gatt_proxy_client *client,
                         uint32_t client_capabilities,
                         struct bgp_hello_response *response);
int bsa_gatt_proxy_register(struct bsa_gatt_proxy_client *client,
                            const struct bgp_uuid *application_uuid,
                            struct bgp_register_response *response);
int bsa_gatt_proxy_deregister(struct bsa_gatt_proxy_client *client,
                              uint8_t server_if,
                              struct bgp_result *response);
int bsa_gatt_proxy_create_service(struct bsa_gatt_proxy_client *client,
                                  uint8_t server_if,
                                  const struct bgp_uuid *service_uuid,
                                  uint8_t instance, int is_primary,
                                  uint16_t num_handles,
                                  struct bgp_result *response);
int bsa_gatt_proxy_add_attribute(struct bsa_gatt_proxy_client *client,
                                 uint16_t service_id,
                                 const struct bgp_uuid *attribute_uuid,
                                 int is_descriptor, uint8_t properties,
                                 uint16_t permissions,
                                 struct bgp_result *response);
int bsa_gatt_proxy_start_service(struct bsa_gatt_proxy_client *client,
                                 uint16_t service_id, uint8_t transport,
                                 struct bgp_result *response);
int bsa_gatt_proxy_stop_service(struct bsa_gatt_proxy_client *client,
                                uint16_t service_id,
                                struct bgp_result *response);
int bsa_gatt_proxy_send_response(struct bsa_gatt_proxy_client *client,
                                 uint32_t cookie, uint8_t att_status,
                                 uint8_t auth_req, const void *value,
                                 uint16_t value_len,
                                 struct bgp_result *response);
int bsa_gatt_proxy_send_indication(struct bsa_gatt_proxy_client *client,
                                   uint16_t conn_id, uint16_t attr_id,
                                   const void *value, uint16_t value_len,
                                   int need_confirm,
                                   struct bgp_result *response);

/*
 * Receive one async event.  timeout_ms is -1 for blocking, 0 for polling, or
 * a positive timeout.  The returned event owns its copied payload.
 */
int bsa_gatt_proxy_receive_event(struct bsa_gatt_proxy_client *client,
                                 struct bsa_gatt_proxy_event *event,
                                 int timeout_ms);

const char *bsa_gatt_proxy_client_status_string(int status);

#ifdef __cplusplus
}
#endif

#endif
