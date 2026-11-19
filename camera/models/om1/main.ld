OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(astro_op37_serializer)

PROVIDE(FW_ORIGINAL_OP37_SERIALIZER = 0x60163ac1);
PROVIDE(FW_WIFI_CHANGE_TIMER_START  = 0x61917002);
PROVIDE(FW_TPC_STAMODE_START        = 0x618c5a7e);
PROVIDE(FW_PTPIP_POST_START         = 0x61802596);
PROVIDE(FW_SLEEP_TIMER_SINGLETON   = 0x60b987b4);
PROVIDE(FW_SLEEP_TIMER_GET_STATE   = 0x60b99010);
PROVIDE(FW_SLEEP_TIMER_REARM       = 0x60b98d97);

PROVIDE(FW_PTPIP_GUID_AUTHORIZE = 0x61804e5f);
PROVIDE(FW_PTPIP_AUTH_CONTINUATION = 0x61802819);
PROVIDE(FW_PTPIP_EVENT_CONTINUATION = 0x618028de);
PROVIDE(FW_PTPIP_EVENT_REJECT = 0x618028f5);
SECTIONS
{
    .astro_patch_descriptor 0x60222360 :
    { KEEP(*(.astro.patch_descriptor)) }
    .astro_patch_ptpip_remote 0x61916908 :
    { KEEP(*(.astro.patch_ptpip_remote)) }
    .astro_patch_sleep_api_state 0x60b1d372 :
    { KEEP(*(.astro.patch_sleep_api_state)) }

    /* Bootstrap-resident before RTOS tasks start; no runtime data in the cave. */
    .astro_text 0x60047760 :
    { KEEP(*(.astro.text)) }
    .astro_ptpip_text 0x600477c0 :
    { KEEP(*(.astro.ptpip_text)) }
    .astro_sleep_api_state_text 0x600477f5 :
    { KEEP(*(.astro.sleep_api_state_text)) }

 .astro_patch_ptpip_local_command 0x61802814 : { KEEP(*(.astro.patch_ptpip_local_command)) }
 .astro_patch_ptpip_local_event 0x618028d9 : { KEEP(*(.astro.patch_ptpip_local_event)) }
 .astro_ptpip_local_text 0x60047d20 : { KEEP(*(.astro.ptpip_local_text)) }
    /DISCARD/ : { *(.comment) *(.note*) *(.data) *(.bss) }
}
ASSERT(SIZEOF(.astro_patch_descriptor) == 4, "serializer pointer is one word")
ASSERT(SIZEOF(.astro_patch_ptpip_remote) == 7, "PTP hook preserves seven bytes")
ASSERT(SIZEOF(.astro_patch_sleep_api_state) == 7, "sleep hook preserves seven bytes")
ASSERT(SIZEOF(.astro_text) == 70, "opaque serializer must remain seventy bytes")
ASSERT(SIZEOF(.astro_ptpip_text) == 51, "PTP tail-call wrapper must remain fifty-one bytes")
ASSERT(SIZEOF(.astro_sleep_api_state_text) == 29, "sleep wrapper must remain twenty-nine bytes")
ASSERT(ADDR(.astro_text) >= 0x60047754, "cave overlaps loader data")
ASSERT(ADDR(.astro_text) + SIZEOF(.astro_text) <= ADDR(.astro_ptpip_text), "serializer overlaps PTP wrapper")
ASSERT(ADDR(.astro_ptpip_text) + SIZEOF(.astro_ptpip_text) <= ADDR(.astro_sleep_api_state_text), "PTP overlaps sleep wrapper")
ASSERT(ADDR(.astro_sleep_api_state_text) + SIZEOF(.astro_sleep_api_state_text) <= 0x60047b00, "wrapper exceeds resident cave")
