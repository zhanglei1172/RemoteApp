import "./styles.css";

const tokenKey = "remoteapp-token";
const app = document.querySelector<HTMLDivElement>("#app")!;
let screenSocket: WebSocket | undefined;
let screenRevision: number | undefined;
let viewGeneration = 0;
type FrameInfo = { revision: number; width: number; height: number };
let incomingRevision: number | undefined;
let pendingFrame: { blob: Blob; info: FrameInfo; generation: number } | undefined;
let decoding = false;
let inputBusy = false;

function invalidateView() {
  ++viewGeneration;
  screenRevision = undefined;
  incomingRevision = undefined;
  pendingFrame = undefined;
  const previous = screenSocket;
  screenSocket = undefined;
  previous?.close();
}

async function drawFrames() {
  if (decoding) return;
  decoding = true;
  try {
    while (pendingFrame) {
      const frame = pendingFrame;
      pendingFrame = undefined;
      const bitmap = await createImageBitmap(frame.blob);
      try {
        if (frame.generation !== viewGeneration || frame.info.revision !== incomingRevision || document.hidden) continue;
        const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
        canvas.width = bitmap.width; canvas.height = bitmap.height;
        canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
        screenRevision = frame.info.revision;
        document.querySelector("#status")!.textContent = `${bitmap.width} × ${bitmap.height}`;
      } finally { bitmap.close(); }
    }
  } catch {
    screenRevision = undefined;
    document.querySelector("#status")!.textContent = "画面解码失败，请刷新";
  } finally { decoding = false; }
}

async function input(path: string, body: unknown) {
  if (inputBusy || screenRevision === undefined) return;
  inputBusy = true;
  try { await api(path, { method: "POST", body: JSON.stringify(body) }); }
  finally { inputBusy = false; }
}

type NormalizedPoint = { x: number; y: number };

function clamp(value: number) {
  return Math.min(1, Math.max(0, value));
}

function normalizedPoint(canvas: HTMLCanvasElement, event: PointerEvent): NormalizedPoint {
  const rect = canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return { x: 0, y: 0 };
  return {
    x: clamp((event.clientX - rect.left) / rect.width),
    y: clamp((event.clientY - rect.top) / rect.height),
  };
}

async function api(path: string, init: RequestInit = {}) {
  const token = sessionStorage.getItem(tokenKey);
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch {
    invalidateView();
    document.querySelector("#status")!.textContent = "连接中断，请检查手机服务";
    return new Response(null, { status: 503 });
  }
  if (response.status === 401 && path !== "/api/v1/pairing/claim") showLogin();
  if (response.status === 409 && path !== "/api/v1/clipboard") {
    const error = await response.clone().json().catch(() => ({})) as { error?: string };
    document.querySelector("#meta")!.textContent = error.error === "INPUT_BUSY" ? "请等待上一操作完成。" : "操作未完成，请检查画面后重新操作。";
    if (error.error === "SCREEN_CHANGED") screenRevision = undefined;
  }
  if (response.status === 503 && path !== "/api/v1/clipboard") document.querySelector("#meta")!.textContent = "请检查手机的无障碍和屏幕捕获权限。";
  return response;
}

function showLogin() {
  sessionStorage.removeItem(tokenKey);
  clipboardEpoch++;
  const clipboardText = document.querySelector<HTMLTextAreaElement>("#clipboardText");
  if (clipboardText) clipboardText.value = "";
  const clipboardMessage = document.querySelector("#clipboardMessage");
  if (clipboardMessage) clipboardMessage.textContent = "";
  invalidateView();
  document.querySelector("#pairing")?.classList.remove("hidden");
  document.querySelector("#console")?.classList.add("hidden");
  document.querySelector("#status")!.textContent = "请登录";
}

function render() {
  app.innerHTML = `
    <main class="shell">
      <header><h1>RemoteApp Host</h1><span id="status">未连接</span></header>
      <section id="pairing" class="card">
        <h2>连接设备</h2><p>输入被控手机上设置的访问密码。</p>
        <form id="passwordForm">
          <div class="pair-row">
            <input id="password" type="password" autocomplete="current-password" enterkeyhint="go" minlength="6" maxlength="128" required placeholder="访问密码" aria-label="访问密码" />
            <button id="login" type="submit">登录</button>
          </div>
          <p id="loginMessage" role="status"></p>
        </form>
        <details><summary>使用一次性配对码</summary>
        <p>在手机 RemoteApp 页面中查看 6 位配对码。</p>
        <form id="pairForm">
          <div class="pair-row">
            <input id="code" inputmode="numeric" autocomplete="one-time-code" enterkeyhint="done" maxlength="6" placeholder="6 位配对码" aria-label="6 位配对码" />
            <button id="pair" type="submit">配对</button>
          </div>
          <p id="pairMessage" role="status"></p>
        </form>
        </details>
      </section>
      <section id="console" class="card hidden">
        <div class="toolbar"><button data-action="BACK">返回</button><button data-action="HOME">主页</button><button data-action="RECENTS">最近任务</button><button id="wake">唤醒屏幕</button><button id="lock">锁定屏幕</button><button id="refresh">刷新</button><button id="revoke">撤销会话</button></div>
        <canvas id="screen"></canvas><p id="meta"></p>
        <section class="clipboard" aria-labelledby="clipboardTitle">
          <h2 id="clipboardTitle">手机剪贴板</h2>
          <p id="clipboardHelp">读取会替换下方文本；写入会覆盖手机剪贴板，空文本可清空文字。Android 10 及以上读取时，请先将手机上的 RemoteApp 切到前台。</p>
          <label for="clipboardText">剪贴板文本</label>
          <textarea id="clipboardText" rows="5" maxlength="65536" autocomplete="off" spellcheck="false" aria-describedby="clipboardHelp"></textarea>
          <div class="clipboard-actions"><button id="clipboardRead">读取手机剪贴板</button><button id="clipboardWrite">写入手机剪贴板</button></div>
          <p id="clipboardMessage" role="status" aria-live="polite"></p>
        </section>
      </section>
    </main>`;
  document.querySelector<HTMLFormElement>("#passwordForm")!.onsubmit = (event) => {
    event.preventDefault();
    void login();
  };
  document.querySelector<HTMLFormElement>("#pairForm")!.onsubmit = (event) => {
    event.preventDefault();
    void pair();
  };
  document.querySelector<HTMLButtonElement>("#clipboardRead")!.onclick = () => void transferClipboard(false);
  document.querySelector<HTMLButtonElement>("#clipboardWrite")!.onclick = () => void transferClipboard(true);
  document.querySelector<HTMLButtonElement>("#lock")!.onclick = async () => {
    const button = document.querySelector<HTMLButtonElement>("#lock")!;
    button.disabled = true;
    try {
      const response = await api("/api/v1/power/lock", { method: "POST" });
      const result = await response.json().catch(() => ({}));
      document.querySelector("#meta")!.textContent = response.ok ? "锁屏请求已提交，远程连接保留。"
        : result.error === "LOCK_PERMISSION_REQUIRED" ? "请在手机 RemoteApp 中点击“启用锁屏权限（Android 8）”并在系统中确认。"
        : "锁屏未完成，请检查手机锁屏权限和无障碍服务。";
    } finally { button.disabled = false; }
  };
  document.querySelector<HTMLButtonElement>("#wake")!.onclick = async () => {
    const button = document.querySelector<HTMLButtonElement>("#wake")!;
    button.disabled = true;
    try {
      const response = await api("/api/v1/power/wake", { method: "POST" });
      if (!response.ok) { document.querySelector("#meta")!.textContent = "唤醒失败，请检查手机无障碍服务或在手机上亮屏。"; return; }
      const result = await response.json();
      document.querySelector("#meta")!.textContent = !result.screenInteractive ? "系统未允许远程亮屏，请在手机上按电源键。"
        : result.keyguardLocked ? "屏幕已唤醒；手机仍处于锁屏，请正常解锁。" : "屏幕已唤醒。";
      if (!screenSocket) await refresh();
    } finally { button.disabled = false; }
  };
  document.querySelector<HTMLButtonElement>("#refresh")!.onclick = refresh;
  document.querySelector<HTMLButtonElement>("#revoke")!.onclick = revoke;
  document.querySelectorAll<HTMLButtonElement>("[data-action]").forEach((button) => {
    button.onclick = () => input("/api/v1/system-action", { action: button.dataset.action });
  });
  if (sessionStorage.getItem(tokenKey)) showConsole();
}

let clipboardBusy = false;
let clipboardEpoch = 0;

async function transferClipboard(write: boolean) {
  if (clipboardBusy) return;
  clipboardBusy = true;
  const epoch = clipboardEpoch;
  const text = document.querySelector<HTMLTextAreaElement>("#clipboardText")!;
  const message = document.querySelector("#clipboardMessage")!;
  const buttons = document.querySelectorAll<HTMLButtonElement>(".clipboard-actions button");
  buttons.forEach(button => button.disabled = true);
  text.disabled = true;
  message.textContent = write ? "正在写入…" : "正在读取…";
  try {
    const response = await api("/api/v1/clipboard", write
      ? { method: "POST", body: JSON.stringify({ text: text.value }) } : {});
    const result = await response.json().catch(() => ({}));
    if (epoch !== clipboardEpoch) return;
    if (!response.ok) {
      const errors: Record<string, string> = {
        CLIPBOARD_FOREGROUND_REQUIRED: "请先将手机上的 RemoteApp 切到前台，再点击读取。",
        CLIPBOARD_NOT_TEXT: "手机剪贴板中不是文本，无法读取。",
        CLIPBOARD_TOO_LARGE: "文本过长，最多支持 65536 个 UTF-16 字符单元。",
        CLIPBOARD_ACCESS_DENIED: "Android 拒绝访问剪贴板，请检查手机权限并将 RemoteApp 切到前台。",
      };
      message.textContent = errors[result.error] ?? "操作失败，请检查连接后重试。";
      return;
    }
    if (!write) text.value = result.text;
    message.textContent = write ? "已写入手机剪贴板。" : result.hasText ? "已读取手机剪贴板。" : "手机剪贴板为空。";
  } catch {
    if (epoch === clipboardEpoch) message.textContent = "操作失败，请检查连接后重试。";
  } finally {
    clipboardBusy = false;
    buttons.forEach(button => button.disabled = false);
    text.disabled = false;
  }
}

async function login() {
  const input = document.querySelector<HTMLInputElement>("#password")!;
  const button = document.querySelector<HTMLButtonElement>("#login")!;
  const message = document.querySelector<HTMLParagraphElement>("#loginMessage")!;
  button.disabled = true;
  message.textContent = "正在登录…";
  try {
    const response = await fetch("/api/v1/auth/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password: input.value }),
    });
    if (!response.ok) {
      message.textContent = "密码错误或尝试过于频繁，请稍后重试。";
      return;
    }
    const data = await response.json() as { token: string };
    sessionStorage.setItem(tokenKey, data.token);
    input.value = "";
    message.textContent = "";
    showConsole();
  } catch {
    message.textContent = "无法连接设备，请检查地址和网络。";
  } finally {
    button.disabled = false;
  }
}

async function pair() {
  const code = document.querySelector<HTMLInputElement>("#code")!.value;
  const button = document.querySelector<HTMLButtonElement>("#pair")!;
  const message = document.querySelector<HTMLParagraphElement>("#pairMessage")!;
  if (!/^\d{6}$/.test(code)) {
    message.textContent = "请输入 6 位数字配对码。";
    return;
  }
  button.disabled = true;
  message.textContent = "正在配对…";
  try {
    const response = await api("/api/v1/pairing/claim", { method: "POST", body: JSON.stringify({ code }) });
    if (!response.ok) {
      message.textContent = "配对失败，请检查配对码和网络。";
      return;
    }
    const data = await response.json() as { token: string };
    sessionStorage.setItem(tokenKey, data.token);
    showConsole();
  } catch {
    message.textContent = "无法连接设备，请检查地址和网络。";
  } finally {
    button.disabled = false;
  }
}

function showConsole() {
  document.querySelector("#pairing")?.classList.add("hidden");
  document.querySelector("#console")?.classList.remove("hidden");
  void refresh().catch(() => { document.querySelector("#status")!.textContent = "连接中断"; });

}

async function refresh() {
  invalidateView();
  if (!sessionStorage.getItem(tokenKey) || document.hidden) return;
  const generation = viewGeneration;
  const response = await api("/api/v1/screen");
  if (generation !== viewGeneration) return;
  if (!response.ok) {
    if (response.status === 503) await stream(generation);
    return;
  }
  const revision = Number(response.headers.get("X-Screen-Revision"));
  const blob = await response.blob();
  if (generation !== viewGeneration || !Number.isSafeInteger(revision)) return;
  incomingRevision = revision;
  pendingFrame = { blob, info: { revision, width: 0, height: 0 }, generation };
  await drawFrames();
  if (generation === viewGeneration) await stream(generation);
}

async function stream(generation: number) {
  const response = await api("/api/v1/stream-ticket", { method: "POST" });
  if (!response.ok || generation !== viewGeneration) return;
  const { ticket } = await response.json() as { ticket: string };
  if (!sessionStorage.getItem(tokenKey) || generation !== viewGeneration) return;
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${location.host}/api/v1/stream?ticket=${encodeURIComponent(ticket)}`);
  screenSocket = socket;
  let info: FrameInfo | undefined;
  socket.onclose = (event) => {
    if (screenSocket !== socket) return;
    invalidateView();
    if (event.code === 1008) showLogin();
    else if (sessionStorage.getItem(tokenKey)) document.querySelector("#status")!.textContent = "画面流中断，请点击刷新";
  };
  socket.binaryType = "blob";
  socket.onmessage = (event) => {
    if (generation !== viewGeneration || screenSocket !== socket) return;
    if (typeof event.data === "string") {
      const message = JSON.parse(event.data);
      if (message.type === "state") {
        screenRevision = undefined;
        incomingRevision = undefined;
        pendingFrame = undefined;
        info = undefined;
        const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
        canvas.getContext("2d")!.clearRect(0, 0, canvas.width, canvas.height);
        document.querySelector("#status")!.textContent = "连接保留";
        document.querySelector("#meta")!.textContent = message.captureState === "screen_off"
          ? "手机屏幕已关闭，点击“唤醒屏幕”可亮屏；安全锁屏仍需正常解锁。"
          : message.captureState === "starting" ? "正在等待新画面…"
          : "连接正常，但系统已停止屏幕共享；请在手机 RemoteApp 中点击“重新授权屏幕共享”。";
        return;
      }
      info = message as FrameInfo;
      document.querySelector("#meta")!.textContent = "";
      if (incomingRevision !== info.revision) screenRevision = undefined;
      incomingRevision = info.revision;
      return;
    }
    if (!info) return;
    pendingFrame = { blob: event.data as Blob, info, generation };
    void drawFrames();
  };
  const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
  let start: { point: NormalizedPoint; pointerId: number; time: number; revision?: number } | undefined;
  canvas.onpointerdown = (event) => {
    if (screenRevision === undefined || inputBusy || (event.pointerType === "mouse" && event.button !== 0)) return;
    event.preventDefault();
    start = { point: normalizedPoint(canvas, event), pointerId: event.pointerId, time: Date.now(), revision: screenRevision };
    canvas.setPointerCapture?.(event.pointerId);
  };
  canvas.onpointerup = (event) => {
    if (!start || start.pointerId !== event.pointerId) return;
    event.preventDefault();
    if (screenRevision === undefined || start.revision !== screenRevision) { start = undefined; return; }
    const to = normalizedPoint(canvas, event);
    const from = start.point;
    const duration = Date.now() - start.time;
    if (Math.hypot(to.x - from.x, to.y - from.y) < 0.02) {
      void input(duration > 550 ? "/api/v1/input/long-press" : "/api/v1/input/tap", { x: to.x, y: to.y, space: "normalized", screenRevision: start.revision });
    } else {
      void input("/api/v1/input/swipe", { from, to, durationMs: Math.min(10000, Math.max(80, duration)), space: "normalized", screenRevision: start.revision });
    }
    start = undefined;
    canvas.releasePointerCapture?.(event.pointerId);
  };
  canvas.onpointercancel = () => { start = undefined; };
  canvas.oncontextmenu = (event) => event.preventDefault();
}

async function revoke() {
  await api("/api/v1/session/revoke", { method: "POST" });
  showLogin();
}

document.addEventListener("visibilitychange", () => {
  if (document.hidden) invalidateView();
  else if (sessionStorage.getItem(tokenKey)) void refresh();
});
window.addEventListener("offline", () => invalidateView());
window.addEventListener("online", () => { if (sessionStorage.getItem(tokenKey)) void refresh(); });

render();
