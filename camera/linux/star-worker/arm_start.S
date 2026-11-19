/* Freestanding Linux/AArch32 process entry and compiler-runtime helpers. */

	.syntax unified
	.arch armv8-a
	.fpu neon-fp-armv8
	.arm
	.eabi_attribute 28, 1	/* Tag_ABI_VFP_args: hard-float AAPCS */

	.section .text.startup._start,"ax",%progbits
	.p2align 2
	.global _start
	.hidden _start
	.type _start,%function
_start:
	ldr	r0, [sp]		/* argc */
	add	r1, sp, #4		/* argv */
	bic	sp, sp, #7		/* AAPCS public-interface alignment */
	bl	main
	mov	r7, #248		/* __NR_exit_group */
	svc	#0
	udf	#0
	.size _start, .-_start

	.section .text.__aeabi_uidiv,"ax",%progbits
	.p2align 2

	.global __aeabi_uidiv
	.hidden __aeabi_uidiv
	.type __aeabi_uidiv,%function
__aeabi_uidiv:
	cmp	r1, #0
	beq	__aeabi_idiv0
	udiv	r0, r0, r1
	bx	lr
	.size __aeabi_uidiv, .-__aeabi_uidiv

	.section .text.__aeabi_uidivmod,"ax",%progbits
	.p2align 2
	.global __aeabi_uidivmod
	.hidden __aeabi_uidivmod
	.type __aeabi_uidivmod,%function
__aeabi_uidivmod:
	cmp	r1, #0
	beq	__aeabi_idiv0
	mov	r2, r0
	udiv	r0, r2, r1
	mls	r1, r0, r1, r2
	bx	lr
	.size __aeabi_uidivmod, .-__aeabi_uidivmod

	.section .text.__aeabi_idiv,"ax",%progbits
	.p2align 2
	.global __aeabi_idiv
	.hidden __aeabi_idiv
	.type __aeabi_idiv,%function
__aeabi_idiv:
	cmp	r1, #0
	beq	__aeabi_idiv0
	sdiv	r0, r0, r1
	bx	lr
	.size __aeabi_idiv, .-__aeabi_idiv

	.section .text.__aeabi_idivmod,"ax",%progbits
	.p2align 2
	.global __aeabi_idivmod
	.hidden __aeabi_idivmod
	.type __aeabi_idivmod,%function
__aeabi_idivmod:
	cmp	r1, #0
	beq	__aeabi_idiv0
	mov	r2, r0
	sdiv	r0, r2, r1
	mls	r1, r0, r1, r2
	bx	lr
	.size __aeabi_idivmod, .-__aeabi_idivmod

	/*
	 * uint64_t quotient/remainder.  Input is n in r0:r1 and d in r2:r3;
	 * the ARM runtime ABI returns q in r0:r1 and remainder in r2:r3.
	 */
	.section .text.__aeabi_uldivmod,"ax",%progbits
	.p2align 2
	.global __aeabi_uldivmod
	.hidden __aeabi_uldivmod
	.type __aeabi_uldivmod,%function
__aeabi_uldivmod:
	orrs	ip, r2, r3
	beq	__aeabi_idiv0
	push	{r4-r10, lr}
	mov	r4, #0		/* quotient low */
	mov	r5, #0		/* quotient high */
	mov	r6, #0		/* remainder low */
	mov	r7, #0		/* remainder high */
	mov	r8, r0		/* shifting numerator low */
	mov	r9, r1		/* shifting numerator high */
	mov	r10, #64
.Luldiv_loop:
	adds	r8, r8, r8
	adcs	r9, r9, r9
	adcs	r6, r6, r6
	adc	r7, r7, r7
	adds	r4, r4, r4
	adc	r5, r5, r5
	cmp	r7, r3
	cmpeq	r6, r2
	blo	.Luldiv_no_subtract
	subs	r6, r6, r2
	sbc	r7, r7, r3
	orr	r4, r4, #1
.Luldiv_no_subtract:
	subs	r10, r10, #1
	bne	.Luldiv_loop
	mov	r0, r4
	mov	r1, r5
	mov	r2, r6
	mov	r3, r7
	pop	{r4-r10, pc}
	.size __aeabi_uldivmod, .-__aeabi_uldivmod

	.section .text.__aeabi_ldivmod,"ax",%progbits
	.p2align 2
	.global __aeabi_ldivmod
	.hidden __aeabi_ldivmod
	.type __aeabi_ldivmod,%function
__aeabi_ldivmod:
	push	{r4-r6, lr}
	asr	r4, r1, #31		/* numerator sign mask */
	asr	r5, r3, #31		/* divisor sign mask */
	cmp	r4, #0
	beq	.Lldiv_numerator_ready
	rsbs	r0, r0, #0
	rsc	r1, r1, #0
.Lldiv_numerator_ready:
	cmp	r5, #0
	beq	.Lldiv_divisor_ready
	rsbs	r2, r2, #0
	rsc	r3, r3, #0
.Lldiv_divisor_ready:
	bl	__aeabi_uldivmod
	eor	r6, r4, r5
	cmp	r6, #0
	beq	.Lldiv_quotient_ready
	rsbs	r0, r0, #0
	rsc	r1, r1, #0
.Lldiv_quotient_ready:
	cmp	r4, #0
	beq	.Lldiv_remainder_ready
	rsbs	r2, r2, #0
	rsc	r3, r3, #0
.Lldiv_remainder_ready:
	pop	{r4-r6, pc}
	.size __aeabi_ldivmod, .-__aeabi_ldivmod

	.section .text.__aeabi_lmul,"ax",%progbits
	.p2align 2
	.global __aeabi_lmul
	.hidden __aeabi_lmul
	.type __aeabi_lmul,%function
__aeabi_lmul:
	mul	r3, r0, r3
	mla	r3, r1, r2, r3
	mov	ip, r0
	umull	r0, r1, ip, r2
	add	r1, r1, r3
	bx	lr
	.size __aeabi_lmul, .-__aeabi_lmul

	.section .text.__aeabi_idiv0,"ax",%progbits
	.p2align 2
	.global __aeabi_idiv0
	.hidden __aeabi_idiv0
	.type __aeabi_idiv0,%function
__aeabi_idiv0:
	udf	#0
	.size __aeabi_idiv0, .-__aeabi_idiv0

	.section .text.__aeabi_ldiv0,"ax",%progbits
	.p2align 2
	.global __aeabi_ldiv0
	.hidden __aeabi_ldiv0
	.type __aeabi_ldiv0,%function
__aeabi_ldiv0:
	udf	#0
	.size __aeabi_ldiv0, .-__aeabi_ldiv0

	.section .note.GNU-stack,"",%progbits
