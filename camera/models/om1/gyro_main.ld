OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(gyro_main_op37_parser)
PROVIDE(FW_STOCK_OP37_PARSER = 0x60163ada);
PROVIDE(FW_ELIGIBLE_EDGE = 0x6015eeaa);
PROVIDE(FW_ELIGIBLE_CONTINUATION = 0x6015ee86);
PROVIDE(FW_SUBMIT_CONTINUATION = 0x6015eeea);
PROVIDE(FW_V3_SLEEP_WRAPPER = 0x600477f5);
PROVIDE(FW_QUEUE_SEND = 0x6016b627);
PROVIDE(FW_BC_BUSY_SET = 0x60162078);
PROVIDE(FW_BC_LADDER = 0x60166452);
PROVIDE(FW_OWNER_REEVALUATE = 0x6015ee2b);
PROVIDE(FW_BC_RECEIVE_CONTINUATION = 0x6015f6a3);
PROVIDE(FW_BC_COMMAND_CLEANUP = 0x6015f7bc);
SECTIONS {
 .gyro_main_patch_rx 0x60163acf : { KEEP(*(.gyro_main.patch_rx)) }
 .gyro_main_patch_parser 0x60222368 : { KEEP(*(.gyro_main.patch_parser)) }
 .gyro_main_patch_eligible 0x6015ee7f : { KEEP(*(.gyro_main.patch_eligible)) }
 .gyro_main_patch_submit 0x6015eee2 : { KEEP(*(.gyro_main.patch_submit)) }
 .gyro_main_patch_wake 0x60b1d372 : { KEEP(*(.gyro_main.patch_wake)) }
 .gyro_main_patch_acquisition 0x6015f69d : { KEEP(*(.gyro_main.patch_bc_receive)) }
 .gyro_main_text 0x60047814 : {
   KEEP(*(.gyro_main.text))
 }
 .gyro_main_acquisition_text 0x60047b00 : { KEEP(*(.gyro_main.acquisition_text)) }
 .gyro_main_mirror 0x60047a80 : { KEEP(*(.gyro_main.mirror)) }
 .gyro_main_lease 0x60047ac0 : { KEEP(*(.gyro_main.lease)) }
 .gyro_main_private 0x60047ad0 : { KEEP(*(.gyro_main.private)) }
 /DISCARD/ : { *(.comment) *(.note*) *(.data) *(.bss) }
}
ASSERT(SIZEOF(.gyro_main_patch_rx)==2,"RX length immediate")
ASSERT(SIZEOF(.gyro_main_patch_parser)==4,"descriptor parser pointer")
ASSERT(SIZEOF(.gyro_main_patch_eligible)==7,"eligibility displaced instruction")
ASSERT(SIZEOF(.gyro_main_patch_submit)==8,"submission displaced instructions")
ASSERT(SIZEOF(.gyro_main_patch_wake)==7,"v3 wake call overlay")
ASSERT(SIZEOF(.gyro_main_patch_acquisition)==6,"BC queue receive displaced store")
ASSERT(ADDR(.gyro_main_text)+SIZEOF(.gyro_main_text)<=0x60047a80,"resident code exceeds its reservation")
ASSERT(SIZEOF(.gyro_main_mirror)==64,"mirror record size")
ASSERT(SIZEOF(.gyro_main_lease)==16,"lease record size")
ASSERT(SIZEOF(.gyro_main_private)==48,"acquisition private record size")
ASSERT(ADDR(.gyro_main_acquisition_text)+SIZEOF(.gyro_main_acquisition_text)<=0x60048000,"acquisition code exceeds resident extension")
