package com.wontlost.aster.aiw.i18n;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 按 setter 名给组件的 I18n bean（AgUiChatI18n、ConversationSidebarI18n）填文案：
 * {@code setInsertCode} 对应键 {@code <prefix>insertCode}。库升级新增字段时，四语资源测试会立刻发现缺键。
 */
public final class I18nBeans {
    private I18nBeans() {}

    /** bean 的全部文案键。 */
    public static List<String> keys(Class<?> beanType, String prefix) {
        return setters(beanType).stream().map(m -> prefix + field(m)).sorted().toList();
    }

    public static <T> T fill(T bean, String prefix, Function<String, String> lookup) {
        for (Method setter : setters(bean.getClass())) {
            invoke(setter, bean, lookup.apply(prefix + field(setter)));
        }
        return bean;
    }

    private static List<Method> setters(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(m -> m.getName().startsWith("set") && m.getParameterCount() == 1)
                .filter(m -> m.getParameterTypes()[0] == String.class)
                .toList();
    }

    private static String field(Method setter) {
        String name = setter.getName().substring(3);
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static void invoke(Method setter, Object bean, String value) {
        try {
            setter.invoke(bean, value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("无法设置文案 " + setter.getName(), e);
        }
    }
}
