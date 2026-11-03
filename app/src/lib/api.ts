// Thin wrapper over the Python server's API. Every endpoint here already
// existed for the vanilla build; nothing on the backend changed for React.

/* Token propagation.
   The server binds the LAN and requires ?k=TOKEN from any non-loopback client.
   The page itself is opened with the token in its URL, but every call below is
   a bare relative "/api/...", which would carry nothing and 401 — the panel
   would render and then fail every request. Rewrite once, here, so no call site
   has to remember. Loopback is exempt server-side, so this is inert locally. */
const TOKEN = new URLSearchParams(location.search).get("k") || "";

export function apiUrl(path: string): string {
  if (!TOKEN) return path;
  return path + (path.includes("?") ? "&" : "?") + "k=" + encodeURIComponent(TOKEN);
}

// EventSource cannot send headers, so the query parameter is the only option.
export function apiEventSource(path: string): EventSource {
  return new EventSource(apiUrl(path));
}

export type DisplayState = { state: number | null; asleep: boolean };

/**
 * "sleep"/"wake" go through macOS (pmset / caffeinate): the signal drops, DDC
 * stays alive, and it comes back remotely. "off" is a DDC power-down of the
 * panel itself — measured on this Acer, it takes the DDC bus with it, so
 * nothing can wake it but the monitor's physical power button.
 */
export type DisplayAction = "sleep" | "wake" | "off" | "on";

export async function getDisplay(): Promise<DisplayState> {
  return (await fetch(apiUrl("/api/display"))).json();
}

export async function setDisplay(action: DisplayAction): Promise<DisplayState> {
  const r = await fetch(apiUrl("/api/display"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ action }),
  });
  if (!r.ok) throw new Error(`display ${action} HTTP ${r.status}`);
  return r.json();
}

export type WatcherState = { installed: boolean; paused: boolean; running: boolean };

export async function getWatcher(): Promise<WatcherState> {
  return (await fetch(apiUrl("/api/watcher"))).json();
}

export async function toggleWatcher(): Promise<WatcherState> {
  const r = await fetch(apiUrl("/api/watcher"), { method: "POST" });
  if (!r.ok) throw new Error(`watcher HTTP ${r.status}`);
  return r.json();
}

export type Ctl = {
  code: string;
  name: string;
  type: "range" | "fader" | "select" | "action" | "info";
  options?: [number, string][];
  tint?: string;
  unit?: string;
  scale?: number;
  hex?: boolean;
  note?: string;
  big?: boolean;
  danger?: boolean;
  confirm?: string;
};

export type Group = {
  id: string;
  name: string;
  blurb: string;
  layout?: "faders" | "input";
  controls: Ctl[];
};

export type Val = { cur: number; max: number };

export async function getCatalog(): Promise<Group[]> {
  const r = await fetch(apiUrl("/api/catalog"));
  if (!r.ok) throw new Error(`catalog HTTP ${r.status}`);
  return (await r.json()).groups;
}

export async function getState(codes: string[]): Promise<Record<string, Val>> {
  // Wrap the COMPLETE url: wrapping the prefix would append the token before
  // the codes and yield /api/state?codes=&k=TOKEN10,62,...
  const r = await fetch(apiUrl("/api/state?codes=" + codes.join(",")));
  if (!r.ok) throw new Error(`state HTTP ${r.status}`);
  return (await r.json()).values;
}

export async function setVcp(code: string, value: number) {
  const r = await fetch(apiUrl("/api/set"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, value }),
  });
  if (!r.ok) throw new Error(`set ${code} HTTP ${r.status}`);
  return r.json();
}

export async function getCaps(): Promise<string> {
  const r = await fetch(apiUrl("/api/caps"));
  return (await r.json()).caps;
}

export type AutoState = { running: boolean; lux: number | null; lid: string };

export async function getAuto(): Promise<AutoState> {
  return (await fetch(apiUrl("/api/auto"))).json();
}

export async function setAuto(on: boolean) {
  await fetch(apiUrl("/api/auto"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(on ? { action: "start" } : { action: "stop" }),
  });
}

export type CycleState = { running: boolean; dwell: number; current: number | null };

export async function getCycle(): Promise<CycleState> {
  return (await fetch(apiUrl("/api/cycle"))).json();
}

export async function setCycle(action: "start" | "stop", dwell?: number) {
  await fetch(apiUrl("/api/cycle"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(action === "stop" ? { action } : { action, dwell }),
  });
}

/** Format an info-type reading for display. */
export function fmt(c: Ctl, v?: number) {
  if (v === undefined || v === null) return "—";
  if (c.hex) return "0x" + v.toString(16).toUpperCase().padStart(4, "0");
  if (c.scale) return (v * c.scale).toFixed(2);
  return String(v);
}

/**
 * Coalesced writer. Dragging a slider fires continuously but a DDC round-trip
 * costs ~100-200ms on a single serial bus, so collapse everything inside a
 * window down to the last value per control, one request in flight at a time.
 */
export function makeWriter(onError: (e: unknown) => void) {
  const pending = new Map<string, number>();
  let timer: number | null = null;
  let inFlight = false;

  async function flush() {
    timer = null;
    if (inFlight) return;
    inFlight = true;
    try {
      while (pending.size) {
        const batch = [...pending.entries()];
        pending.clear();
        for (const [code, value] of batch) {
          try {
            await setVcp(code, value);
          } catch (e) {
            onError(e);
          }
        }
      }
    } finally {
      inFlight = false;
    }
  }

  return (code: string, value: number) => {
    pending.set(code, value);
    if (timer === null) timer = window.setTimeout(flush, 130);
  };
}

export type AutostartState = {
  installed: boolean;
  enabled: boolean;
  label: string;
  plist: string;
};

export async function getAutostart(): Promise<AutostartState> {
  return (await fetch(apiUrl("/api/autostart"))).json();
}

export async function setAutostart(enabled: boolean): Promise<AutostartState> {
  const r = await fetch(apiUrl("/api/autostart"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled }),
  });
  return r.json();
}

export type DimState = { level: number; floor: number };

export async function getDim(): Promise<DimState> {
  return (await fetch(apiUrl("/api/dim"))).json();
}

export async function setDim(level: number): Promise<DimState> {
  const r = await fetch(apiUrl("/api/dim"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ level }),
  });
  return r.json();
}
