OUTPUT_FORMAT("elf32-mn10300")
OUTPUT_ARCH(mn10300)
ENTRY(astro_control_downstream_dispatch)

/* Stock call ABI retained by the source-owned compact dispatcher. */
PROVIDE(FW_STOCK_DOWNSTREAM = 0x40019c46);
PROVIDE(FW_CONTINUATION = 0x40019866);
PROVIDE(FW_STOCK_INTEGRATE = 0x40019a65);
PROVIDE(FW_STOCK_OUTPUT = 0x40019b0c);
PROVIDE(FW_AFTER_NATIVE_PAIR = 0x40019873);
PROVIDE(FW_MODE_CONTINUATION = 0x4001eabc);

SECTIONS
{
    .astro_preposition_text 0x40008c00 :
    {
        KEEP(*(.astro.preposition_text))
    }
    /* Erased stock cave.  This one section owns the complete native DDA
     * consumer and stock-gated dispatcher. */
    .astro_control_text 0x40008e00 :
    {
        KEEP(*(.astro.control_text))
    }

    /* Exact-width replacement of the displaced stock downstream CALL. */
    .astro_control_entry_redirect 0x40019861 :
    {
        KEEP(*(.astro.control_entry_redirect))
    }

    /* Two MOV axis/CALL pairs, exactly 12 bytes, before native limiting. */
    .astro_preposition_native_pair_redirect 0x40019867 :
    {
        KEEP(*(.astro.preposition_native_pair_redirect))
    }
    .astro_preposition_mode_redirect 0x4001eab3 :
    {
        KEEP(*(.astro.preposition_mode_redirect))
    }

    /* Four capacity constants reclaim trace records 45 through 47. */
    .astro_control_ring_write_wrap 0x4000926d :
    {
        KEEP(*(.astro.control_ring_write_wrap))
    }

    .astro_control_ring_count_capacity 0x400092a9 :
    {
        KEEP(*(.astro.control_ring_count_capacity))
    }

    .astro_control_ring_oldest_wrap 0x400092b4 :
    {
        KEEP(*(.astro.control_ring_oldest_wrap))
    }

    .astro_control_ring_dump_wrap 0x400094ab :
    {
        KEEP(*(.astro.control_ring_dump_wrap))
    }

    /DISCARD/ :
    {
        *(.comment)
        *(.note*)
        *(.data)
        *(.bss)
    }
}

ASSERT(SIZEOF(.astro_control_text) <= 0x200,
       "v3 compact control text escapes erased 0x40008e00..0x40008fff cave")
ASSERT(SIZEOF(.astro_preposition_text) <= 0x200,
       "preposition text escapes erased 0x40008c00..0x40008dff cave")
ASSERT(SIZEOF(.astro_preposition_native_pair_redirect) == 12,
       "native pair redirect must replace exactly two MOV axis/CALL pairs")
ASSERT(SIZEOF(.astro_preposition_mode_redirect) == 9,
       "mode redirect must replace exactly the three mode-state MOVs")
ASSERT(SIZEOF(.astro_control_entry_redirect) == 5,
       "v3 entry redirect must remain exactly one far JMP")
ASSERT(SIZEOF(.astro_control_ring_write_wrap) == 2,
       "v3 ring write-wrap edit must remain exactly two bytes")
ASSERT(SIZEOF(.astro_control_ring_count_capacity) == 2,
       "v3 ring count-capacity edit must remain exactly two bytes")
ASSERT(SIZEOF(.astro_control_ring_oldest_wrap) == 2,
       "v3 ring oldest-wrap edit must remain exactly two bytes")
ASSERT(SIZEOF(.astro_control_ring_dump_wrap) == 2,
       "v3 ring dump-wrap edit must remain exactly two bytes")
