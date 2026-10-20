package com.wontlost.aster.aiw.auth;

import java.util.Set;

/** 工作区支持的界面语言；其它取值一律归一为 en。 */
final class Locales {
    private static final Set<String> SUPPORTED = Set.of("en", "zh", "de", "hi");

    private Locales() {}

    static String normalize(String locale) {
        return locale != null && SUPPORTED.contains(locale) ? locale : "en";
    }
}
