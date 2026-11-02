import "./styles.css";

const tokenKey = "remoteapp-token";
const app = document.querySelector<HTMLDivElement>("#app")!;

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
        <input id="code" inputmode="numeric" maxlength="6" placeholder="6 位配对码" />
        <button id="pair">配对</button><p id="pairMessage"></p>
      </section>
      <section id="console" class="card hidden">
        <div class="toolbar"><button data-action="BACK">返回</button><button data-action="HOME">主页</button><button data-action="RECENTS">最近任务</button><button id="refresh">刷新</button><button id="revoke">撤销会话</button></div>
        <canvas id="screen"></canvas><p id="meta"></p>
      </section>
    </main>`;
  document.querySelector<HTMLButtonElement>("#pair")!.onclick = pair;
  document.querySelector<HTMLButtonElement>("#refresh")!.onclick = refresh;
  document.querySelector<HTMLButtonElement>("#revoke")!.onclick = revoke;
  document.querySelectorAll<HTMLButtonElement>("[data-action]").forEach((button) => {
    button.onclick = () => api("/api/v1/system-action", { method: "POST", body: JSON.stringify({ action: button.dataset.action }) });
  });
  if (sessionStorage.getItem(tokenKey)) showConsole();
}

async function pair() {
  const code = document.querySelector<HTMLInputElement>("#code")!.value;
  const response = await api("/api/v1/pairing/claim", { method: "POST", body: JSON.stringify({ code }) });
  const message = document.querySelector<HTMLParagraphElement>("#pairMessage")!;
  if (!response.ok) { message.textContent = "配对失败，请检查配对码和网络。"; return; }
  const data = await response.json() as { token: string };
  sessionStorage.setItem(tokenKey, data.token);
  showConsole();
  refresh();
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
  canvas.width = bitmap.width; canvas.height = bitmap.height;
  canvas.getContext("2d")!.drawImage(bitmap, 0, 0);
  document.querySelector("#status")!.textContent = `${bitmap.width} × ${bitmap.height}`;
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
  };
  const canvas = document.querySelector<HTMLCanvasElement>("#screen")!;
  let start: { x: number; y: number; time: number } | undefined;
  canvas.onpointerdown = (event) => { start = { x: event.offsetX, y: event.offsetY, time: Date.now() }; };
  canvas.onpointerup = (event) => {
    if (!start) return;
    const x = event.offsetX / canvas.clientWidth, y = event.offsetY / canvas.clientHeight;
    const from = { x: start.x / canvas.clientWidth, y: start.y / canvas.clientHeight };
    const duration = Date.now() - start.time;
    if (Math.hypot(x - from.x, y - from.y) < 0.02) {
      api(duration > 550 ? "/api/v1/input/long-press" : "/api/v1/input/tap", { method: "POST", body: JSON.stringify({ x, y, space: "normalized" }) });
    } else {
      api("/api/v1/input/swipe", { method: "POST", body: JSON.stringify({ from, to: { x, y }, durationMs: Math.max(80, duration), space: "normalized" }) });
    }
    start = undefined;
  };
}

async function revoke() {
  await api("/api/v1/session/revoke", { method: "POST" });
  sessionStorage.removeItem(tokenKey); location.reload();
}

render();
