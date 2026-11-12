package com.savenest.bookmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExportCsvTest {

    @Test
    void quotesSpecialCharacters() {
        assertThat(ExportController.cell("plain")).isEqualTo("plain");
        assertThat(ExportController.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ExportController.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ExportController.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
    }

    @Test
    void neutralizesFormulaInjection() {
        assertThat(ExportController.cell("=HYPERLINK(\"x\")")).startsWith("\"'=");
        assertThat(ExportController.cell("+1")).isEqualTo("'+1");
        assertThat(ExportController.cell("-1")).isEqualTo("'-1");
        assertThat(ExportController.cell("@cmd")).isEqualTo("'@cmd");
        assertThat(ExportController.cell("")).isEmpty();
    }
}
