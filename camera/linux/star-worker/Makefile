REPOSITORY := $(abspath ../../..)
TOOLCHAIN_BIN := $(REPOSITORY)/.toolchains/gcc-arm-10.3-2021.07-x86_64-arm-none-linux-gnueabihf/bin
TOOL_PREFIX := $(TOOLCHAIN_BIN)/arm-none-linux-gnueabihf-

# GNU make predefines CXX, AS, and sometimes NM.  Replace only those built-in
# defaults, while preserving explicit command-line/environment overrides.
ifneq ($(filter default undefined,$(origin CXX)),)
CXX := $(TOOL_PREFIX)g++
endif
ifneq ($(filter default undefined,$(origin NM)),)
NM := $(TOOL_PREFIX)nm
endif
ifneq ($(filter default undefined,$(origin AS)),)
AS := $(TOOL_PREFIX)as
endif
ifneq ($(filter default undefined,$(origin STRIP)),)
STRIP := $(TOOL_PREFIX)strip
endif
ifneq ($(filter default undefined,$(origin READELF)),)
READELF := $(TOOL_PREFIX)readelf
endif
ifneq ($(filter default undefined,$(origin SIZE)),)
SIZE := $(TOOL_PREFIX)size
endif

OUTPUT_DIR ?= ../../../build/om1-stars
PROGRAM := $(OUTPUT_DIR)/om1-stars
AUDIT_ELF := $(OUTPUT_DIR)/om1-stars.audit
LINK_MAP := $(OUTPUT_DIR)/om1-stars.map
DIAGNOSTIC_PROGRAM := $(OUTPUT_DIR)/om1-stars-diag
DIAGNOSTIC_AUDIT_ELF := $(OUTPUT_DIR)/om1-stars-diag.audit
DIAGNOSTIC_LINK_MAP := $(OUTPUT_DIR)/om1-stars-diag.map
QEMU_ARM ?= qemu-arm

# Regression ceilings, not allocation targets.  The detector intentionally
# uses one fixed BSS workspace; these bounds catch accidental full-frame
# buffers or a surprising binary-size increase while remaining overrideable
# for an explicitly reviewed change.
MAX_BSS_BYTES ?= 4194304
# ABI v11 keeps static catalogs and small dynamic STATE frames separate and
# leaves all diagnostic transports out of the delivery link.  Keep a
# deliberate on-disk ceiling while the underlying camera-control backend is
# simplified independently.
MAX_BINARY_BYTES ?= 163840

# Production inputs deliberately exclude every HTTP, direct-file, decoder
# benchmark, and one-shot capture CLI.  Those are linked only by the optional
# `diagnostic` target below.
COMMON_CPP_SOURCES := \
	camera.cpp \
	detector.cpp \
	devmem_state.cpp \
	orf.cpp \
	ptp.cpp \
	runtime.cpp \
	setting_catalog_direct.cpp \
	syscalls.cpp \
	thread_pipeline.cpp \
	util.cpp
PRODUCTION_CPP_SOURCES := main.cpp
DIAGNOSTIC_CPP_SOURCES := \
	diagnostic_main.cpp \
	fileif_direct.cpp \
	http.cpp
ASM_SOURCES := \
	arm_start.S \
	firmware_constants.S \
	thread_start.S
COMMON_CPP_OBJECTS := $(COMMON_CPP_SOURCES:%.cpp=$(OUTPUT_DIR)/%.o)
PRODUCTION_CPP_OBJECTS := \
	$(PRODUCTION_CPP_SOURCES:%.cpp=$(OUTPUT_DIR)/%.o)
DIAGNOSTIC_CPP_OBJECTS := \
	$(DIAGNOSTIC_CPP_SOURCES:%.cpp=$(OUTPUT_DIR)/%.o)
ASM_OBJECTS := $(ASM_SOURCES:%.S=$(OUTPUT_DIR)/%.o)
OBJECTS := $(ASM_OBJECTS) $(COMMON_CPP_OBJECTS) $(PRODUCTION_CPP_OBJECTS)
DIAGNOSTIC_OBJECTS := \
	$(ASM_OBJECTS) $(COMMON_CPP_OBJECTS) $(DIAGNOSTIC_CPP_OBJECTS)
DEPS := \
	$(COMMON_CPP_OBJECTS:.o=.d) \
	$(PRODUCTION_CPP_OBJECTS:.o=.d) \
	$(DIAGNOSTIC_CPP_OBJECTS:.o=.d) \
	$(ASM_OBJECTS:.o=.d)

ARCH_FLAGS := \
	-march=armv8-a \
	-marm \
	-mfpu=neon-fp-armv8 \
	-mfloat-abi=hard \
	-mabi=aapcs-linux

REQUIRED_CXXFLAGS := \
	$(ARCH_FLAGS) \
	-std=c++17 \
	-Os \
	-flto \
	-ffreestanding \
	-nostdinc++ \
	-fno-builtin \
	-fno-pic \
	-fno-pie \
	-fno-common \
	-fomit-frame-pointer \
	-ffunction-sections \
	-fdata-sections \
	-fno-exceptions \
	-fno-rtti \
	-fno-unwind-tables \
	-fno-asynchronous-unwind-tables \
	-fno-threadsafe-statics \
	-fno-use-cxa-atexit \
	-fno-stack-protector \
	-fvisibility=hidden \
	-fno-ident \
	-Wall \
	-Wextra \
	-Wpedantic \
	-Werror \
	-Wvla \
	-Walloca

REQUIRED_LDFLAGS := \
	$(ARCH_FLAGS) \
	-flto \
	-nostdlib \
	-nostartfiles \
	-nodefaultlibs \
	-static \
	-no-pie \
	-Wl,-e,_start \
	-Wl,-O1 \
	-Wl,--gc-sections \
	-Wl,--no-undefined \
	-Wl,--fatal-warnings \
	-Wl,--build-id=none \
	-Wl,-z,noexecstack \
	-Wl,-z,max-page-size=4096 \
	-Wl,-z,common-page-size=4096

.PHONY: all audit smoke check size diagnostic diagnostic-smoke \
	diagnostic-size clean help check-toolchain
.DELETE_ON_ERROR:

all: $(PROGRAM)

check-toolchain:
	@for tool in '$(CXX)' '$(STRIP)' '$(READELF)' '$(NM)' '$(SIZE)'; do \
		command -v "$$tool" >/dev/null 2>&1 || { \
			echo "missing ARMHF build tool: $$tool" >&2; \
			echo "override CXX/STRIP/READELF/NM/SIZE with a compatible arm-linux-gnueabihf suite" >&2; \
			exit 2; \
		}; \
	done
	@test "$$('$(CXX)' -dumpmachine)" = arm-none-linux-gnueabihf -o \
	      "$$('$(CXX)' -dumpmachine)" = arm-linux-gnueabihf || { \
		echo "$(CXX): expected an arm*-linux-gnueabihf compiler" >&2; \
		exit 2; \
	}

$(OUTPUT_DIR):
	mkdir -p $@

$(OUTPUT_DIR)/%.o: %.cpp | $(OUTPUT_DIR) check-toolchain
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) $(REQUIRED_CXXFLAGS) \
		-MMD -MP -c -o $@ $<

# Keep the freestanding memory routines as ordinary ELF definitions.  GCC can
# synthesize memcpy/memset only after LTO reachability has been decided; making
# this one small object non-LTO prevents those late references from losing
# their implementations.
$(OUTPUT_DIR)/runtime.o: runtime.cpp | $(OUTPUT_DIR) check-toolchain
	$(CXX) $(CPPFLAGS) $(CXXFLAGS) \
		$(filter-out -flto,$(REQUIRED_CXXFLAGS)) -fno-lto \
		-MMD -MP -c -o $@ $<

$(OUTPUT_DIR)/%.o: %.S | $(OUTPUT_DIR) check-toolchain
	$(CXX) $(CPPFLAGS) $(ARCH_FLAGS) -fno-pic -fno-pie \
		-MMD -MP -c -o $@ $<

# No LDLIBS are accepted here: the link must be satisfied entirely by these
# objects, including compiler-runtime helpers in arm_start.S/runtime.cpp.
$(AUDIT_ELF): $(OBJECTS)
	$(CXX) $(CXXFLAGS) $(REQUIRED_CXXFLAGS) $(LDFLAGS) \
		$(REQUIRED_LDFLAGS) -Wl,-Map,$(LINK_MAP) -o $@ $(OBJECTS)

$(PROGRAM): $(AUDIT_ELF)
	cp $< $@
	$(STRIP) --strip-all $@

audit: $(PROGRAM)
	@set -eu; \
	readelf_h="$$('$(READELF)' --wide --file-header '$(AUDIT_ELF)')"; \
	printf '%s\n' "$$readelf_h" | grep -Eq 'Class:[[:space:]]+ELF32' || { \
		echo "$(AUDIT_ELF): expected ELF32" >&2; exit 1; }; \
	printf '%s\n' "$$readelf_h" | grep -Eq 'Data:[[:space:]]+2.s complement, little endian' || { \
		echo "$(AUDIT_ELF): expected little-endian ELF" >&2; exit 1; }; \
	printf '%s\n' "$$readelf_h" | grep -Eq 'Machine:[[:space:]]+ARM' || { \
		echo "$(AUDIT_ELF): expected ARM machine" >&2; exit 1; }; \
	printf '%s\n' "$$readelf_h" | grep -Eq 'Type:[[:space:]]+EXEC' || { \
		echo "$(AUDIT_ELF): expected a fixed-address executable" >&2; exit 1; }; \
	printf '%s\n' "$$readelf_h" | grep -Eq 'Version5 EABI' || { \
		echo "$(AUDIT_ELF): expected EABI5" >&2; exit 1; }; \
	printf '%s\n' "$$readelf_h" | grep -Eq 'hard-float ABI' || { \
		echo "$(AUDIT_ELF): expected hard-float ELF flag" >&2; exit 1; }; \
	entry_address="$$(printf '%s\n' "$$readelf_h" | \
		sed -n 's/.*Entry point address:[[:space:]]*//p')"; \
	start_address="0x$$('$(NM)' --defined-only --format=posix \
		'$(AUDIT_ELF)' | awk '$$1 == "_start" { print $$3; exit }')"; \
	if test "$$start_address" = 0x || \
	   test "$$((entry_address))" -ne "$$((start_address))"; then \
		echo "$(AUDIT_ELF): ELF entry point is not _start" >&2; exit 1; \
	fi; \
	'$(READELF)' --wide --arch-specific '$(AUDIT_ELF)' | \
		grep -q 'Tag_ABI_VFP_args: VFP registers' || { \
		echo "$(AUDIT_ELF): expected VFP-register argument ABI" >&2; exit 1; }; \
	program_headers="$$('$(READELF)' --wide --program-headers '$(AUDIT_ELF)')"; \
	if printf '%s\n' "$$program_headers" | grep -Eq '^[[:space:]]*INTERP[[:space:]]'; then \
		echo "$(AUDIT_ELF): PT_INTERP is forbidden" >&2; exit 1; \
	fi; \
	if printf '%s\n' "$$program_headers" | grep -Eq '^[[:space:]]*DYNAMIC[[:space:]]'; then \
		echo "$(AUDIT_ELF): PT_DYNAMIC is forbidden" >&2; exit 1; \
	fi; \
	printf '%s\n' "$$program_headers" | \
		awk '/GNU_STACK/ { found = 1; if ($$0 ~ /RWE/) bad = 1 } \
			END { exit !(found && !bad) }' || { \
		echo "$(AUDIT_ELF): missing GNU_STACK or executable stack" >&2; exit 1; }; \
	if printf '%s\n' "$$program_headers" | awk '/^[[:space:]]*LOAD/ && /RWE/ { found=1 } END { exit !found }'; then \
		echo "$(AUDIT_ELF): writable/executable PT_LOAD is forbidden" >&2; exit 1; \
	fi; \
	if '$(READELF)' --wide --dynamic '$(AUDIT_ELF)' 2>&1 | \
		grep -Eq '[(](NEEDED|RPATH|RUNPATH)[)]'; then \
		echo "$(AUDIT_ELF): dynamic dependency or search path is forbidden" >&2; exit 1; \
	fi; \
	if grep -Eq '[.]a[(][^)]*[)]|Archive member included' '$(LINK_MAP)'; then \
		echo "$(AUDIT_ELF): linked archive/library code is forbidden" >&2; exit 1; \
	fi; \
	undefined="$$('$(NM)' --demangle --undefined-only '$(AUDIT_ELF)')"; \
	if test -n "$$undefined"; then \
		echo "$(AUDIT_ELF): unresolved symbols are forbidden" >&2; \
		printf '%s\n' "$$undefined" >&2; exit 1; \
	fi; \
	all_symbols="$$('$(NM)' --demangle '$(AUDIT_ELF)')"; \
	if printf '%s\n' "$$all_symbols" | grep -Eq \
		'(^|[[:space:]])(malloc|calloc|realloc|free|aligned_alloc|posix_memalign)$$|operator (new|delete)|std::|(_Unwind_|__gxx_personality|__cxa_|__aeabi_unwind_cpp)'; then \
		echo "$(AUDIT_ELF): allocator, standard-library, or C++ runtime symbol is forbidden" >&2; exit 1; \
	fi; \
	if '$(READELF)' --wide --sections '$(AUDIT_ELF)' | \
		grep -Eq '[.](preinit_array|init_array|fini_array|ctors|dtors|tdata|tbss|eh_frame|ARM[.]exidx)([[:space:]]|$$)'; then \
		echo "$(AUDIT_ELF): constructors, destructors, TLS, and unwind sections are forbidden" >&2; exit 1; \
	fi; \
	if '$(READELF)' --wide --relocs '$(AUDIT_ELF)' | \
		grep -q '^Relocation section'; then \
		echo "$(AUDIT_ELF): runtime relocations are forbidden" >&2; exit 1; \
	fi; \
	if '$(READELF)' --wide --sections '$(PROGRAM)' | grep -q '[.]symtab'; then \
		echo "$(PROGRAM): delivery binary was not fully stripped" >&2; exit 1; \
	fi; \
	bss_bytes="$$('$(SIZE)' --format=SysV '$(AUDIT_ELF)' | \
		awk '$$1 == ".bss" { print $$2; exit }')"; \
	if test -z "$$bss_bytes" || test "$$bss_bytes" -gt '$(MAX_BSS_BYTES)'; then \
		echo "$(AUDIT_ELF): BSS $$bss_bytes exceeds $(MAX_BSS_BYTES)-byte review ceiling" >&2; exit 1; \
	fi; \
	binary_bytes="$$(wc -c < '$(PROGRAM)')"; \
	if test "$$binary_bytes" -gt '$(MAX_BINARY_BYTES)'; then \
		echo "$(PROGRAM): $$binary_bytes bytes exceeds $(MAX_BINARY_BYTES)-byte review ceiling" >&2; exit 1; \
	fi; \
	echo "audit passed: _start entry; static ARM EABI5 hard-float; no interpreter, dependencies, unresolved symbols, heap, C++ runtime, unwind data, RWX load, or executable stack; size ceilings met"
	@$(SIZE) $(AUDIT_ELF) $(PROGRAM)
	@wc -c $(AUDIT_ELF) $(PROGRAM)

# This host-only smoke test executes only argument parsing and output through
# the target's own raw syscalls.  It therefore catches broken _start argc/argv
# forwarding and an unbootable static image without contacting the camera.
smoke: audit
	@command -v '$(QEMU_ARM)' >/dev/null 2>&1 || { \
		echo "missing ARM user-mode emulator: $(QEMU_ARM)" >&2; exit 2; }
	@'$(QEMU_ARM)' '$(PROGRAM)' --help >/dev/null
	@set -eu; status=0; \
		'$(QEMU_ARM)' '$(PROGRAM)' --not-a-mode >/dev/null 2>&1 || status=$$?; \
		test "$$status" -eq 64 || { \
			echo "QEMU invalid-mode smoke test returned $$status, expected 64" >&2; exit 1; \
		}
	@echo "smoke passed: static ARM startup, argc/argv, and write/exit syscalls"

$(DIAGNOSTIC_AUDIT_ELF): $(DIAGNOSTIC_OBJECTS)
	$(CXX) $(CXXFLAGS) $(REQUIRED_CXXFLAGS) $(LDFLAGS) \
		$(REQUIRED_LDFLAGS) -Wl,-Map,$(DIAGNOSTIC_LINK_MAP) \
		-o $@ $(DIAGNOSTIC_OBJECTS)

$(DIAGNOSTIC_PROGRAM): $(DIAGNOSTIC_AUDIT_ELF)
	cp $< $@
	$(STRIP) --strip-all $@

diagnostic: $(DIAGNOSTIC_PROGRAM)

diagnostic-smoke: diagnostic
	@command -v '$(QEMU_ARM)' >/dev/null 2>&1 || { \
		echo "missing ARM user-mode emulator: $(QEMU_ARM)" >&2; exit 2; }
	@'$(QEMU_ARM)' '$(DIAGNOSTIC_PROGRAM)' --help >/dev/null
	@set -eu; status=0; \
		'$(QEMU_ARM)' '$(DIAGNOSTIC_PROGRAM)' --raw not-a-camera-path.ORF \
			>/dev/null 2>&1 || status=$$?; \
		test "$$status" -eq 2 || { \
			echo "QEMU raw-path validation returned $$status, expected 2" >&2; exit 1; \
		}
	@set -eu; status=0; \
		message="$$('$(QEMU_ARM)' '$(DIAGNOSTIC_PROGRAM)' --raw-direct \
			not-a-camera-path.ORF 2>&1)" || status=$$?; \
		test "$$status" -eq 2 || { \
			echo "QEMU direct path validation returned $$status, expected 2" >&2; exit 1; \
		}; \
		printf '%s\n' "$$message" | \
			grep -q 'fileif path must start with /DCIM/' || { \
			echo "QEMU direct path validation did not reject before device access" >&2; exit 1; \
		}
	@set -eu; status=0; \
		message="$$('$(QEMU_ARM)' '$(DIAGNOSTIC_PROGRAM)' --raw-direct-slot2 \
			/DCIM/../P8153896.ORF 2>&1)" || status=$$?; \
		test "$$status" -eq 2 || { \
			echo "QEMU Slot-2 path validation returned $$status, expected 2" >&2; exit 1; \
		}; \
		printf '%s\n' "$$message" | \
			grep -q 'fileif path has an unsafe segment' || { \
			echo "QEMU Slot-2 traversal validation was not specific" >&2; exit 1; \
		}
	@echo "diagnostic smoke passed: CLI and raw-path validation"

check: smoke

size: $(PROGRAM)
	$(SIZE) $(AUDIT_ELF) $(PROGRAM)
	@wc -c $(AUDIT_ELF) $(PROGRAM)

diagnostic-size: $(DIAGNOSTIC_PROGRAM)
	$(SIZE) $(DIAGNOSTIC_AUDIT_ELF) $(DIAGNOSTIC_PROGRAM)
	@wc -c $(DIAGNOSTIC_AUDIT_ELF) $(DIAGNOSTIC_PROGRAM)

clean:
	$(RM) -r $(OUTPUT_DIR)

help:
	@echo "make           cross-build stripped ARMHF $(PROGRAM)"
	@echo "make audit     enforce freestanding ELF, ABI, dependency, symbol, and W^X invariants"
	@echo "make smoke     run audit plus host QEMU startup/argument/syscall checks"
	@echo "make check     alias for smoke"
	@echo "make size      report linked-section and on-disk sizes"
	@echo "make diagnostic       build the separate om1-stars-diag tool"
	@echo "make diagnostic-smoke run the diagnostic CLI smoke tests"
	@echo "make diagnostic-size  report diagnostic binary sizes"
	@echo "make clean     remove this tool's build directory"

-include $(DEPS)
