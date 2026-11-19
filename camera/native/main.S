/*
 * MAIN relays one Linux-owned native command through the next stock opcode-37
 * request. The unused request tail holds at most twenty opaque body bytes;
 * Linux publishes its length (14 PRE or 20 RATE) last and never overwrites a
 * nonempty slot. MAIN copies the body, releases the slot, then commits TX length.
 * Stock owns allocation, cadence, transport, completion and the six-byte reply.
 * GAVE injection and MOVESTEP telemetry are intentionally absent.
 */

    .am33_2
    .equ MAILBOX_PAYLOAD_BYTES, 20
    .equ MAILBOX_PAYLOAD_BASE,  0xa2985fa4
    .equ MAILBOX_READY,         MAILBOX_PAYLOAD_BASE + MAILBOX_PAYLOAD_BYTES
    .equ PREPOSITION_BODY_BYTES, 14
    .equ RATE_BODY_BYTES,        20
    .equ OP37_REQUEST,           0x62985f84
    .equ PTP_PROFILE_STATE,      0x62cde314
    .equ PTP_PROFILE_MODE,       PTP_PROFILE_STATE + 2

    .extern FW_ORIGINAL_OP37_SERIALIZER
    .extern FW_WIFI_CHANGE_TIMER_START
    .extern FW_TPC_STAMODE_START
    .extern FW_PTPIP_POST_START
    .extern FW_SLEEP_TIMER_SINGLETON
    .extern FW_SLEEP_TIMER_GET_STATE
    .extern FW_SLEEP_TIMER_REARM

    .section .astro.patch_ptpip_remote, "ax", @progbits
    .global astro_patch_ptpip_remote
astro_patch_ptpip_remote:
    call    astro_ptpip_remote_wrapper,[],4

    .section .astro.patch_sleep_api_state, "ax", @progbits
    .global astro_patch_sleep_api_state
astro_patch_sleep_api_state:
    call    astro_sleep_api_state_wrapper,[],8

/* Descriptor index 24, serializer field. */
    .section .astro.patch_descriptor, "a", @progbits
    .global astro_patch_descriptor
astro_patch_descriptor:
    .long   astro_op37_serializer

    .section .astro.text, "ax", @progbits

/*
 * CALLS enters this descriptor serializer.  The twelve-byte local area keeps
 * the nested CALLS link/flushback below the MOVM save.  d3/a3 retain the
 * original request/transaction arguments across the stock serializer.
 */
    .global astro_op37_serializer
astro_op37_serializer:
    movm    [d3,a2,a3],(sp)
    add     -12,sp
    mov     a0,d3
    mov     a1,a3
    mov     FW_ORIGINAL_OP37_SERIALIZER,a0
    calls   (a0)
    cmp     OP37_REQUEST,d3
    bne     .Lserializer_return
    mov     MAILBOX_READY,a1
    movbu   (a1),d0
    cmp     PREPOSITION_BODY_BYTES,d0
    beq     .Lopaque_length_valid
    cmp     RATE_BODY_BYTES,d0
    bne     .Lserializer_return
.Lopaque_length_valid:
    mov     (4,a3),a0
    cmp     0,a0
    beq     .Lserializer_return
    mov     d0,d3
    inc     d3
    mov     a1,a2
    add     -MAILBOX_PAYLOAD_BYTES,a1
.Lserializer_copy:
    movbu   (a1),d1
    inc     a1
    inc     a0
    movbu   d1,(a0)
    add     -1,d0
    bne     .Lserializer_copy
    movbu   d0,(a2)
    mov     d3,(8,a3)
.Lserializer_return:
    ret     [d3,a2,a3],24

    .section .astro.ptpip_text, "ax", @progbits

/*
 * This splice is reached only inside the existing stock BLE code-11 path,
 * after its unchanged pass-check gate.  Replay its Wi-Fi-change timer setup
 * exactly.  For action 1 (enter remote mode), synchronously run the stock TPC
 * station-mode facade so QTPCCTRL owns profile-9 registration.  Only expose
 * the stock PTP/IP listener after the facade succeeds and the completed
 * initializer's lifecycle and mode stores prove that profile 9 is active.
 *
 * The stock action/result dispatch immediately following the splice remains
 * unchanged.  No serializer, BLE protocol, socket, owner, or CPU0 path is
 * replaced.
 */
    .global astro_ptpip_remote_wrapper
astro_ptpip_remote_wrapper:
    mov     0,a0
    call    FW_WIFI_CHANGE_TIMER_START,[],4
    cmp     1,a3
    bne     .Lptpip_remote_return

    call    FW_TPC_STAMODE_START,[d2],8
    cmp     0,d0
    bne     .Lptpip_remote_return

    mov     PTP_PROFILE_STATE,a0
    movbu   (a0),d0
    cmp     1,d0
    bne     .Lptpip_remote_return

    movhu   (PTP_PROFILE_MODE - PTP_PROFILE_STATE,a0),d0
    cmp     9,d0
    bne     .Lptpip_remote_return

    jmp     FW_PTPIP_POST_START
.Lptpip_remote_return:
    ret     [],4

    .section .astro.sleep_api_state_text, "ax", @progbits

/*
 * Preserve the exact stock state query and its d0 result.  Only when that
 * query reports RUNNING (zero) reacquire the singleton and invoke the same
 * private activity-rearm primitive used by the stock SleepTimer observer.
 * The primitive's mutation-byte guard may reject a racing attempt.  Returning
 * d0=0 then resumes the untouched stock RUNNING/STOPPED/UNKNOWN print path.
 */
    .global astro_sleep_api_state_wrapper
astro_sleep_api_state_wrapper:
    call    FW_SLEEP_TIMER_GET_STATE,[],8
    cmp     0,d0
    bne     .Lsleep_api_state_return

    call    FW_SLEEP_TIMER_SINGLETON,[],4
    call    FW_SLEEP_TIMER_REARM,[a2],8
    clr     d0
.Lsleep_api_state_return:
    ret     [],8
