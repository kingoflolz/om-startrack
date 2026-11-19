/* Local STAR sessions do not require a retained PC pairing. Only the kernel's
 * accepted AF_INET peer 127.0.0.1 receives this exception; all other command
 * connections execute the unchanged stock GUID-tail authorization routine.
 *
 * Both splices are JMPs in stock PTP accept task 0x6180d982, so SP still names
 * its 420-byte frame. isc_socket returns the native Linux sockaddr unchanged:
 * length at SP+48, family at SP+196 and network-order address at SP+200.
 * SP+416 is unused stock padding after the 16-byte responder GUID at SP+400.
 * Save locality there because accepting the event socket overwrites sockaddr.
 * It is rewritten for every command-init attempt and never persisted.
 */
 .am33_2
 .equ PEER_LENGTH,48
 .equ PEER_FAMILY,196
 .equ PEER_ADDRESS,200
 .equ LOCAL_COMMAND,416
 .equ LOCALHOST_LE,0x0100007f

 .section .astro.patch_ptpip_local_command,"ax",@progbits
 jmp astro_ptpip_local_command

 .section .astro.patch_ptpip_local_event,"ax",@progbits
 jmp astro_ptpip_local_event

 .section .astro.ptpip_local_text,"ax",@progbits
 .global astro_ptpip_local_command
astro_ptpip_local_command:
 clr d0
 mov d0,(LOCAL_COMMAND,sp)
 mov (PEER_LENGTH,sp),d0
 cmp 16,d0
 bne .Lcommand_stock
 movhu (PEER_FAMILY,sp),d0
 cmp 2,d0
 bne .Lcommand_stock
 mov (PEER_ADDRESS,sp),d0
 cmp LOCALHOST_LE,d0
 bne .Lcommand_stock
 mov 1,d0
 mov d0,(LOCAL_COMMAND,sp)
 jmp FW_PTPIP_AUTH_CONTINUATION
.Lcommand_stock:
 /* a0 still points at the received GUID tail. Preserve the original call ABI. */
 call FW_PTPIP_GUID_AUTHORIZE,[d2,d3,a2,a3],24
 jmp FW_PTPIP_AUTH_CONTINUATION

 .global astro_ptpip_local_event
astro_ptpip_local_event:
 mov (LOCAL_COMMAND,sp),d0
 cmp 1,d0
 bne .Levent_stock
 mov (PEER_LENGTH,sp),d0
 cmp 16,d0
 bne .Levent_reject
 movhu (PEER_FAMILY,sp),d0
 cmp 2,d0
 bne .Levent_reject
 mov (PEER_ADDRESS,sp),d0
 cmp LOCALHOST_LE,d0
 bne .Levent_reject
.Levent_stock:
 /* Displaced receive-buffer and capacity loads. */
 mov (16,sp),d0
 mov 16448,d1
 jmp FW_PTPIP_EVENT_CONTINUATION
.Levent_reject:
 /* Stock close, fd=-1 and event-accept retry; keep the command socket alive. */
 jmp FW_PTPIP_EVENT_REJECT
