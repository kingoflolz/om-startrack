	/* Heap-free AArch32 clone trampoline for one fixed worker stack. */
	.syntax unified
	.arch armv8-a
	.fpu neon-fp-armv8
	.arm
	.eabi_attribute 28, 1	/* Tag_ABI_VFP_args: hard-float AAPCS */

	.section .text.om1_clone_fixed_thread,"ax",%progbits
	.p2align 2
	.global om1_clone_fixed_thread
	.hidden om1_clone_fixed_thread
	.type om1_clone_fixed_thread,%function
om1_clone_fixed_thread:
	/*
	 * r0 entry, r1 argument, r2 aligned stack top, r3 clone flags,
	 * fifth argument clear_tid at the caller's original [sp].  r4 and r7
	 * are callee-saved in AAPCS and must be restored on the parent path.
	 */
	push	{r4, r7, lr}
	mov	ip, r0
	mov	r4, r1
	bic	r1, r2, #15
	sub	r1, r1, #8
	str	ip, [r1]
	str	r4, [r1, #4]
	mov	r0, r3
	mov	r2, #0		/* parent_tidptr */
	mov	r3, #0		/* TLS is intentionally unused */
	ldr	r4, [sp, #12]	/* child clear_tid pointer */
	mov	r7, #120		/* __NR_clone */
	svc	#0
	cmp	r0, #0
	beq	.Lfixed_child
	pop	{r4, r7, pc}

.Lfixed_child:
	ldr	ip, [sp]
	ldr	r0, [sp, #4]
	add	sp, sp, #8
	blx	ip
	mov	r0, #0
	mov	r7, #1		/* __NR_exit: this worker only */
	svc	#0
	udf	#0
	.size om1_clone_fixed_thread, .-om1_clone_fixed_thread

	.section .note.GNU-stack,"",%progbits
