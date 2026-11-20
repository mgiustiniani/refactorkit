package org.refactorkit.cli.previewcommand

import java.nio.file.Path

/** Stateless first-party source/expected-image data; no scenario state is shared. */
internal object CHeaderFieldRenameSample {
    fun material(field: String, macro: Boolean = false): Map<Path, String> = linkedMapOf(
        Path.of("include/api.h") to (if (macro) "#define DECLARE_FIELD(name) int name;\n" else "") +
            "struct meter_reading {\n" +
            (if (macro) "    DECLARE_FIELD($field)\n" else "    int $field;\n") +
            "    int calibration;\n};\nstruct other_reading { int raw_count; };\nstruct meter_reading read_meter(void);\n",
        Path.of("src/read_meter.c") to "#include \"api.h\"\nstruct meter_reading read_meter(void) {\n" +
            "    return (struct meter_reading) { .calibration = 1, .$field = 42 };\n}\n",
        Path.of("src/main.c") to "#include \"api.h\"\nint main(void) {\n    const char *label = \"raw_count\";\n" +
            "    struct meter_reading reading = read_meter();\n    struct other_reading other = { .raw_count = 7 };\n" +
            "    /* raw_count is not a reference here. */\n    return reading.$field + other.raw_count + (label[0] == 'r' ? 0 : 1);\n}\n",
    )

}
