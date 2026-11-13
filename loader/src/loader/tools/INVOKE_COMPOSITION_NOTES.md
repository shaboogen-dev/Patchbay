# REPLACE_INVOKE composition — technical contract

This is a deliberately narrow accumulator-chaining operation, not a generic promise that two arbitrary method replacement helpers are compatible.

`InvokeReplacer` retains the original caller's static invocation descriptor, inserts a private static synthetic method in the original owner and calls each bridge in application order. Every bridge receives unchanged non-final arguments and the previous bridge's result as the final argument. That final reference argument must have the exact return descriptor. Each bridge returns exactly the original invocation return type and accepts each original argument type unchanged or widened to `java.lang.Object` for reference/array types, and the original opcode must be INVOKESTATIC.

The adapter is branch-free; it preserves all existing bytecode offsets. The generated method uses a new constant-pool method reference and synthetic method declaration; JVM deep verification is required. No JDK-internal ASM/bytecode APIs are used.

`JavaPreparer` checks exact original physical method + call opcode, owner, method, descriptor and original call number (below), with ordinal case-sensitive matching; hooks must be consecutive in physical owner order. Unsupported collisions fail closed before the second invoke patch runs, naming both mod/hook IDs. All participating hooks remain in `composition-report.tsv`.

If two helpers share the stack shape but are not semantically composable, resolve the mod contract; the loader cannot prove runtime behavioral compatibility from descriptors alone. Real Bitwig 6.1.3 Windows validation is required.

## Preview 26 correction (real-mod signature)

The real Device Window Helper bridge is `(Ljava/lang/String;Ldvm;)Ldvm;`.
The real Plugin Undo bridge is `(Ljava/lang/String;Ljava/lang/Object;)Ldvm;`.
Java permits a `dvm` reference to be passed to an `Object` parameter, so the
synthesized method may invoke both while retaining its own `(String,dvm)dvm`
ABI. This is strictly an argument-widening rule; returns and reference-accumulator
invariant remain exact, and other conversion paths are rejected.

The mod implementation sources show both wrappers add Settings controls and
return a widget, but only a live Windows run can establish correct combined UI
presentation and semantics.

## Occurrence numbering

A manifest's `occurrence` counts the matching invocations left after **that mod's
own** earlier hooks on the same match key. A mod can declare two hooks with the
same match key and `occurrence=1` to patch two different widgets built by two
separate INVOKEVIRTUAL instructions. The Remote Control Page Buttons Previous/Next
hooks use this pattern. Other mods' hooks don't affect the count.

`JavaPreparer` turns each hook into its call number in the original method, one mod
at a time. Hooks from different mods with one call number are one site: the
constrained chain with exact or `Object`-widened arguments, or a refusal naming
them. `InvokeReplacer` counts the invocations still in the class it is given, so
`JavaPreparer` passes the call number minus the lower-numbered calls already replaced.
Do not extend the chaining adapter to arbitrary virtual calls or void-return
methods merely to bypass a local repeated-site conflict.
