OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(astro_op37_serializer)

PROVIDE(FW_ORIGINAL_OP37_SERIALIZER = 0x6016736c);
PROVIDE(FW_WIFI_CHANGE_TIMER_START  = 0x61929c35);
PROVIDE(FW_TPC_SYNCHRONOUS_COMMAND = 0x618d5b3b);
PROVIDE(FW_PTPIP_POST_START         = 0x6180d834);
PROVIDE(FW_SLEEP_TIMER_SINGLETON   = 0x60ba55d1);
PROVIDE(FW_SLEEP_TIMER_GET_STATE   = 0x60ba5e2d);
PROVIDE(FW_SLEEP_TIMER_REARM       = 0x60ba5bb4);

PROVIDE(FW_TPC_DISPATCH_REST = 0x618d40a0);
PROVIDE(FW_TPC_START_HANDLER = 0x618d40c6);
PROVIDE(FW_TPC_SELECTOR_CONTINUATION = 0x618d40f1);
PROVIDE(FW_TPC_COMPLETE_REST = 0x618d43e2);
PROVIDE(FW_TPC_SYNCHRONOUS_REPLY = 0x618d4410);
PROVIDE(FW_TPC_ASYNCHRONOUS_REPLY = 0x618d43f4);
PROVIDE(FW_PTPIP_GUID_AUTHORIZE = 0x6181016a);
PROVIDE(FW_PTPIP_AUTH_CONTINUATION = 0x6180dab7);
PROVIDE(FW_PTPIP_EVENT_CONTINUATION = 0x6180db7c);
PROVIDE(FW_PTPIP_EVENT_REJECT = 0x6180db93);
SECTIONS
{
    .astro_patch_descriptor 0x6023706c :
    { KEEP(*(.astro.patch_descriptor)) }
    .astro_patch_ptpip_remote 0x6192953b :
    { KEEP(*(.astro.patch_ptpip_remote)) }
    .astro_patch_sleep_api_state 0x60b28223 :
    { KEEP(*(.astro.patch_sleep_api_state)) }

    /* Bootstrap-resident before RTOS tasks start; no runtime data in the cave. */
    .astro_text 0x60047810 :
    { KEEP(*(.astro.text)) }
    .astro_ptpip_text 0x60047860 :
    { KEEP(*(.astro.ptpip_text)) }
    .astro_sleep_api_state_text 0x600478c0 :
    { KEEP(*(.astro.sleep_api_state_text)) }
    .astro_patch_tpc_dispatch 0x618d409a : { KEEP(*(.astro.patch_tpc_dispatch)) }
    .astro_patch_tpc_selector 0x618d40e7 : { KEEP(*(.astro.patch_tpc_selector)) }
    .astro_patch_tpc_complete 0x618d43db : { KEEP(*(.astro.patch_tpc_complete)) }
    .astro_tpc_text 0x60047c40 : { KEEP(*(.astro.tpc_text)) }
    .astro_patch_ptpip_local_command 0x6180dab2 : { KEEP(*(.astro.patch_ptpip_local_command)) }
    .astro_patch_ptpip_local_event 0x6180db77 : { KEEP(*(.astro.patch_ptpip_local_event)) }
    .astro_ptpip_local_text 0x60047d20 : { KEEP(*(.astro.ptpip_local_text)) }
    /DISCARD/ : { *(.comment) *(.note*) *(.data) *(.bss) }
}
ASSERT(SIZEOF(.astro_patch_descriptor) == 4, "serializer pointer is one word")
ASSERT(SIZEOF(.astro_patch_ptpip_remote) == 7, "PTP hook preserves seven bytes")
ASSERT(SIZEOF(.astro_patch_sleep_api_state) == 7, "sleep hook preserves seven bytes")
ASSERT(SIZEOF(.astro_text) == 70, "opaque serializer must remain seventy bytes")
ASSERT(SIZEOF(.astro_ptpip_text) == 55, "PTP tail-call wrapper must remain fifty-five bytes")
ASSERT(SIZEOF(.astro_sleep_api_state_text) == 29, "sleep wrapper must remain twenty-nine bytes")
ASSERT(ADDR(.astro_text) >= 0x60047808, "cave overlaps loader data")
ASSERT(ADDR(.astro_text) + SIZEOF(.astro_text) <= ADDR(.astro_ptpip_text), "serializer overlaps PTP wrapper")
ASSERT(ADDR(.astro_ptpip_text) + SIZEOF(.astro_ptpip_text) <= ADDR(.astro_sleep_api_state_text), "PTP overlaps sleep wrapper")
ASSERT(ADDR(.astro_sleep_api_state_text) + SIZEOF(.astro_sleep_api_state_text) <= 0x60047b00, "wrapper exceeds resident cave")

ASSERT(SIZEOF(.astro_patch_tpc_dispatch)==6,"TPC dispatcher displacement")
ASSERT(SIZEOF(.astro_patch_tpc_selector)==10,"TPC selector displacement")
ASSERT(SIZEOF(.astro_patch_tpc_complete)==7,"TPC completion displacement")
ASSERT(SIZEOF(.astro_patch_ptpip_local_command)==5,"PTP command authorization displacement")
ASSERT(SIZEOF(.astro_patch_ptpip_local_event)==5,"PTP event receive displacement")
ASSERT(ADDR(.astro_tpc_text)+SIZEOF(.astro_tpc_text)<=ADDR(.astro_ptpip_local_text),"TPC helpers overlap local PTP helpers")
ASSERT(ADDR(.astro_ptpip_local_text)+SIZEOF(.astro_ptpip_local_text)<=0x60048000,"local PTP helpers escape early cave")
