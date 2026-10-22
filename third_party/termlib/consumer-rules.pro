# Terminal.cpp resolves these classes, constructors and fields by JNI string
# literals. Keep only that boundary; Kotlin accessors and data-class helpers can
# still be optimized. These rules must travel with the library to its consumers.
-keep class org.connectbot.terminal.CellRun {
    <fields>;
}
-keep class org.connectbot.terminal.TermRect {
    <init>(int, int, int, int);
}
-keep class org.connectbot.terminal.CursorPosition {
    <init>(int, int);
}
-keep class org.connectbot.terminal.ScreenCell {
    <init>(char, java.util.List, int, int, int, int, int, int, boolean, boolean, int, boolean, boolean, int);
    <fields>;
}
-keep class org.connectbot.terminal.TerminalProperty
-keep class org.connectbot.terminal.TerminalProperty$BoolValue {
    <init>(boolean);
}
-keep class org.connectbot.terminal.TerminalProperty$IntValue {
    <init>(int);
}
-keep class org.connectbot.terminal.TerminalProperty$StringValue {
    <init>(java.lang.String);
}
-keep class org.connectbot.terminal.TerminalProperty$ColorValue {
    <init>(int, int, int);
}

# Native code looks methods up on the concrete callback object, including the
# anonymous implementation. Preserve the interface so this rule remains valid
# through optimization, and preserve all callback method names/signatures.
-keep interface org.connectbot.terminal.TerminalCallbacks { <methods>; }
-keepclassmembers,includedescriptorclasses class * implements org.connectbot.terminal.TerminalCallbacks {
    public int damage(int, int, int, int);
    public int moverect(org.connectbot.terminal.TermRect, org.connectbot.terminal.TermRect);
    public int moveCursor(org.connectbot.terminal.CursorPosition, org.connectbot.terminal.CursorPosition, boolean);
    public int setTermProp(int, org.connectbot.terminal.TerminalProperty);
    public int bell();
    public int pushScrollbackLine(int, org.connectbot.terminal.ScreenCell[], boolean);
    public int popScrollbackLine(int, org.connectbot.terminal.ScreenCell[]);
    public int clearScrollback();
    public int onKeyboardInput(byte[]);
    public int onOscSequence(int, java.lang.String, int, int);
}

# The exported Java_org_connectbot_terminal_TerminalNative_* symbols require
# stable names even when a consuming app does not use AGP's default JNI rules.
-keepclasseswithmembernames,includedescriptorclasses class org.connectbot.terminal.TerminalNative {
    native <methods>;
}
