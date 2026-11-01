# Hermes Companion has no reflection-heavy dependencies.

# sherpa-onnx native code reads its config classes by field name over JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Shizuku starts the agent display service by class name in a shell process.
-keep class cloud.stormlabs.hermescompanion.AgentDisplayService { *; }
-keep class cloud.stormlabs.hermescompanion.IAgentDisplay** { *; }
