import "./styles.css";

const tokenKey = "remoteapp-token";
const app = document.querySelector<HTMLDivElement>("#app")!;

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

function api(path: string, init: RequestInit = {}) {
  const token = sessionStorage.getItem(tokenKey);
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return fetch(path, { ...init, headers });
}

function render() {
  app.innerHTML = `
    <main class="shell">
      <header><h1>RemoteApp Host</h1><span id="status">未连接</span></header>
      <section id="pairing" class="card">
        <h2>连接设备</h2><p>在手机 RemoteApp 页面中查看一次性配对码。</p>
        <form id="pairForm">
          <div class="pair-row">
            <input id="code" inputmode="numeric" autocomplete="one-time-code" enterkeyhint="done" maxlength="6" placeholder="6 位配对码" aria-label="6 位配对码" />
            <button id="pair" type="submit">配对</button>
          </div>
          <p id="pairMessage" role="status"></p>
        </form>
      </section>
      <section id="console" class="card hidden">
        <div class="toolbar"><button data-action="BACK">返回</button><button data-action="HOME">主页</button><button data-action="RECENTS">最近任务</button><button id="refresh">刷新</button><button id="revoke">撤销会话</button></div>
        <canvas id="screen"></canvas><p id="meta"></p>
      </section>
    </main>`;
  document.querySelector<HTMLFormElement>("#pairForm")!.onsubmit = (event) => {
    event.preventDefault();
    void pair();
  };
  document.querySelector<HTMLButtonElement>("#refresh")!.onclick = refresh;
  document.querySelector<HTMLButtonElement>("#revoke")!.onclick = revoke;
  document.querySelectorAll<HTMLButtonElement>("[data-action]").forEach((button) => {
    button.onclick = () => api("/api/v1/system-action", { method: "POST", body: JSON.stringify({ action: button.dataset.action }) });
  });
  if (sessionStorage.getItem(tokenKey)) showConsole();
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
    void refresh();
  } catch {
    message.textContent = "无法连接设备，请检查地址和网络。";
  } finally {
    button.disabled = false;
  }
}

function showConsole() {
  document.querySelector("#pairing")?.classList.add("hidden");
  document.querySelector("#console")?.classList.remove("hidden");
  stream();
}

async function refresh() {
  const response = await api("/api/v1/screen");
  if (!response.ok) return;
  const blob = await response.blob();
  const bitmap = await createImageBitmap(blob);
  const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
  const { width, height } = bitmap;
  canvas.width = width; canvas.height = height;
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
  bitmap.close();
  document.querySelector("#status")!.textContent = `${width} × ${height}`;
}

function stream() {
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${location.host}/api/v1/stream`);
  socket.binaryType = "blob";
  socket.onmessage = async (event) => {
    if (typeof event.data === "string") return;
    const bitmap = await createImageBitmap(event.data);
    const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
    canvas.width = bitmap.width; canvas.height = bitmap.height;
    canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
    bitmap.close();
  };
  const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
  let start: { point: NormalizedPoint; pointerId: number; time: number } | undefined;
  canvas.onpointerdown = (event) => {
    if (event.pointerType === "mouse" && event.button !== 0) return;
    event.preventDefault();
    start = { point: normalizedPoint(canvas, event), pointerId: event.pointerId, time: Date.now() };
    canvas.setPointerCapture?.(event.pointerId);
  };
  canvas.onpointerup = (event) => {
    if (!start || start.pointerId !== event.pointerId) return;
    event.preventDefault();
    const to = normalizedPoint(canvas, event);
    const from = start.point;
    const duration = Date.now() - start.time;
    if (Math.hypot(to.x - from.x, to.y - from.y) < 0.02) {
      void api(duration > 550 ? "/api/v1/input/long-press" : "/api/v1/input/tap", { method: "POST", body: JSON.stringify({ x: to.x, y: to.y, space: "normalized" }) });
    } else {
      void api("/api/v1/input/swipe", { method: "POST", body: JSON.stringify({ from, to, durationMs: Math.max(80, duration), space: "normalized" }) });
    }
    start = undefined;
    canvas.releasePointerCapture?.(event.pointerId);
  };
  canvas.onpointercancel = () => { start = undefined; };
  canvas.oncontextmenu = (event) => event.preventDefault();
}

async function revoke() {
  await api("/api/v1/session/revoke", { method: "POST" });
  sessionStorage.removeItem(tokenKey); location.reload();
}

render();
