/**
 * 编辑器侧栏 iframe 与 aster-cloud 父页面之间的 postMessage 桥（ADR 0049 §2.5，协议 v1）。
 *
 * - 只向配置的 cloud origin 发送（绝不使用通配目标），只接受来自该 origin 的父窗口消息；
 * - 父页面推送 aster.ai.context（且 policyId 一致）→ 写入聊天组件的 context，AsterAgent 据此读取策略源码；
 * - 代码块「插入 / 替换」→ aster.ai.apply，代码原样转发（可能以换行结尾）；generate 不会自动写回；
 * - reauth()：服务端发现会话用户已不在时调用（票据过期但用户仍在则服务端直接续签，不走这里）
 *   → aster.ai.reauth，父页面重建 iframe 重新交接（每次加载只发一次，避免循环）。
 */

type ContextItem = { description: string; value: string };
type ChatElement = HTMLElement & { context: ContextItem[] };
type CodeActionDetail = { action: string; code: string; language: string; messageId: string };
type Inbound = Record<string, unknown>;

/** 上一次 init 注册的监听；Vaadin 重建视图时先拆掉，避免旧组件仍在收发。 */
let dispose: (() => void) | null = null;
/** 当前 init 的重新交接请求；未初始化（不在 iframe 中）时为空操作。 */
let requestReauth: () => void = () => {};

function contextItems(data: Inbound, policyId: string): ContextItem[] {
  const text = (v: unknown) => (typeof v === 'string' ? v : '');
  return [
    { description: 'policy.source', value: text(data.source) },
    { description: 'policy.cnlLocale', value: text(data.cnlLocale) },
    { description: 'policy.id', value: policyId },
  ];
}

function init(chat: ChatElement, cloudOrigin: string, policyId: string): void {
  dispose?.();
  dispose = null;
  requestReauth = () => {};
  if (window.parent === window) {
    // 直接打开 /panel（不在 iframe 中）时没有父页面可通信
    return;
  }
  const post = (message: Inbound) => window.parent.postMessage(message, cloudOrigin);
  let reauthSent = false;

  const onMessage = (event: MessageEvent) => {
    if (event.origin !== cloudOrigin || event.source !== window.parent) return;
    const data = event.data as Inbound | null;
    if (typeof data !== 'object' || data === null || data.v !== 1) return;
    if (data.type === 'aster.ai.context' && data.policyId === policyId) {
      chat.context = contextItems(data, policyId);
    } else if (data.type === 'aster.ai.applied' && data.ok !== true) {
      console.warn('aster-bridge: editor rejected apply', data.requestId, data.reason);
    }
  };
  const onCodeAction = (event: Event) => {
    const { action, code } = (event as CustomEvent<CodeActionDetail>).detail;
    if (action !== 'insert' && action !== 'replace') return;
    post({ type: 'aster.ai.apply', v: 1, action, code, policyId, requestId: crypto.randomUUID() });
  };
  requestReauth = () => {
    if (reauthSent) return;
    reauthSent = true;
    post({ type: 'aster.ai.reauth', v: 1 });
  };

  window.addEventListener('message', onMessage);
  chat.addEventListener('agui-code-action', onCodeAction);
  dispose = () => {
    window.removeEventListener('message', onMessage);
    chat.removeEventListener('agui-code-action', onCodeAction);
  };
  // 监听就绪后再宣告，父页面随即回推当前策略上下文
  post({ type: 'aster.ai.ready', v: 1 });
}

declare global {
  interface Window {
    asterBridge: { init: typeof init; reauth: () => void };
  }
}

window.asterBridge = { init, reauth: () => requestReauth() };

export {};
