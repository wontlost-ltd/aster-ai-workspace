package com.wontlost.aster.aiw.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * 应用外观：以 Lumo 为底，再叠加 Aster 令牌（{@code aster/tokens.css}）与版式（{@code aster/views.css}），
 * 对 {@code /}、{@code /panel}、{@code /audit} 一律生效。Vaadin 25 起以 {@link StyleSheet} 加载静态样式
 * （{@code @Theme} 已弃用），文件位于 {@code META-INF/resources/aster/}，字体随之自托管。
 */
@StyleSheet(Lumo.STYLESHEET)
@StyleSheet("aster/tokens.css")
@StyleSheet("aster/views.css")
public class AsterAppShell implements AppShellConfigurator {}
