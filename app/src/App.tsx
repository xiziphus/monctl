import { useCallback, useEffect, useRef, useState } from "react";
import {
  getCatalog, getState, setVcp, getCaps, getAuto, setAuto, getCycle, setCycle,
  getAutostart, setAutostart, getDim, setDim,
  apiEventSource, getWatcher, toggleWatcher, getDisplay, setDisplay,
  makeWriter, fmt, type Ctl, type Group, type Val, type AutoState,
  type AutostartState, type WatcherState, type DisplayState,
} from "@/lib/api";

/* Geist. Panels are bg-100 on a bg-200 page, hairline gray-400 border, 12px
   radius. Type is Geist Sans; every number is Geist Mono so columns align. */

const card =
  "rounded-[12px] border border-gray-400 bg-bg-100";
const label =
  "text-[clamp(14px,1vw,19px)] text-gray-900";
const num =
  "font-mono tabular-nums text-gray-1000";
const hdr =
  "text-[clamp(17px,1.35vw,26px)] font-medium text-gray-1000";

/**
 * Everything the menu bar app can do, one press each.
 *
 * The control catalogue below can already reach every one of these VCP codes,
 * but hunting 0xD6 in a 38-row table is not the same as having a Sleep button.
 *
 * Display power is TWO different things and the panel says so, because getting
 * it wrong strands you:
 *   Sleep / Wake — macOS drops the signal; DDC stays alive; wakes remotely.
 *   Power off    — the panel powers itself down and takes its DDC controller
 *                  with it. Measured on this Acer: nothing on the wire can wake
 *                  it, only the monitor's physical button. Hence danger styling
 *                  and a confirm.
 */
function QuickActions({ onVcp, onError }:
  { onVcp: (code: string, value: number) => void; onError: (e: unknown) => void }) {
  const [disp, setDisp] = useState<DisplayState | null>(null);
  const [watch, setWatch] = useState<WatcherState | null>(null);
  const [confirmOff, setConfirmOff] = useState(false);

  const refresh = useCallback(async () => {
    try { setDisp(await getDisplay()); } catch (e) { onError(e); }
    try { setWatch(await getWatcher()); } catch { setWatch(null); }
  }, [onError]);

  useEffect(() => { refresh(); }, [refresh]);

  const act = async (a: "sleep" | "wake" | "off" | "on") => {
    try { setDisp(await setDisplay(a)); } catch (e) { onError(e); }
  };

  const Row = ({ label: l, children }: { label: string; children: React.ReactNode }) => (
    <div className="flex flex-wrap items-center gap-2 py-2.5">
      <span className="w-[92px] shrink-0 text-[11px] uppercase tracking-wide text-gray-800">{l}</span>
      {children}
    </div>
  );

  return (
    <div className="divide-y divide-gray-400/60">
      <Row label="Display">
        <Btn onClick={() => act("sleep")}>Sleep</Btn>
        <Btn variant="primary" onClick={() => act("wake")}>Wake</Btn>
        {confirmOff ? (
          <>
            <Btn variant="danger" onClick={async () => { setConfirmOff(false); await act("off"); }}>
              Confirm power off
            </Btn>
            <Btn onClick={() => setConfirmOff(false)}>Cancel</Btn>
          </>
        ) : (
          <Btn variant="danger" onClick={() => setConfirmOff(true)}>Power off</Btn>
        )}
        <span className="text-[11px] uppercase tracking-wide text-gray-800">
          {disp ? (disp.asleep ? "asleep" : "on") : "—"}
        </span>
      </Row>

      {confirmOff && (
        <p className="py-2 text-[12px] text-red-400">
          Power off kills the panel’s DDC bus. Nothing can wake it over the network —
          you will need its physical power button. Use Sleep instead.
        </p>
      )}

      <Row label="Input">
        <Btn onClick={() => onVcp("60", 15)}>DisplayPort</Btn>
        <Btn onClick={() => onVcp("60", 17)}>HDMI 1</Btn>
        <Btn onClick={() => onVcp("60", 18)}>HDMI 2</Btn>
      </Row>

      <Row label="Audio">
        <Btn onClick={() => onVcp("8D", 1)}>Unmute</Btn>
        <Btn onClick={() => onVcp("8D", 2)}>Mute</Btn>
      </Row>

      <Row label="Brightness">
        {[10, 25, 50, 75, 100].map((v) => (
          <Btn key={v} onClick={() => onVcp("10", v)}>{v}</Btn>
        ))}
      </Row>

      <Row label="Watcher">
        {watch && watch.installed ? (
          <>
            <Btn onClick={async () => {
              try { setWatch(await toggleWatcher()); } catch (e) { onError(e); }
            }}>
              {watch.paused ? "Resume" : "Pause"}
            </Btn>
            <span className="text-[11px] uppercase tracking-wide text-gray-800">
              {/* A stale flag from a dead watcher looks exactly like a healthy
                  pause; resuming it would appear to do nothing. */}
              {watch.running ? (watch.paused ? "running · paused" : "running") : "not running"}
            </span>
          </>
        ) : (
          <span className="text-[11px] uppercase tracking-wide text-gray-800">not installed</span>
        )}
      </Row>
    </div>
  );
}

/**
 * Phone-only Tier 1: the controls you need when you CANNOT SEE THE SCREEN.
 *
 * That is the whole justification for this block. If the panel is asleep or on
 * the wrong input, the desktop UI is invisible and the phone is the only way
 * back. So wake/sleep and input switching sit above the tabs and stay on screen
 * no matter which tab is open — the phone is a remote control, not a small
 * desktop.
 */
function Remote({ onVcp, onError }:
  { onVcp: (code: string, value: number) => void; onError: (e: unknown) => void }) {
  const [disp, setDisp] = useState<DisplayState | null>(null);
  useEffect(() => { getDisplay().then(setDisp).catch(() => {}); }, []);
  const act = async (a: "sleep" | "wake") => {
    try { setDisp(await setDisplay(a)); } catch (e) { onError(e); }
  };
  const tap = "h-12 flex-1 rounded-[8px] border text-[13px] font-medium active:opacity-70";
  return (
    <div className={`${card} shrink-0 p-3 lg:hidden`}>
      <div className="flex gap-2">
        <button type="button" onClick={() => act("wake")}
                className={`${tap} border-transparent bg-gray-1000 text-bg-200`}>Wake</button>
        <button type="button" onClick={() => act("sleep")}
                className={`${tap} border-gray-400 bg-bg-100 text-gray-1000`}>Sleep</button>
        <span className="flex w-16 shrink-0 items-center justify-center text-[11px]
                         uppercase tracking-wide text-gray-800">
          {disp ? (disp.asleep ? "asleep" : "on") : "—"}
        </span>
      </div>
      <div className="mt-2 flex gap-2">
        {([["DP", 15], ["HDMI 1", 17], ["HDMI 2", 18]] as [string, number][]).map(([n, v]) => (
          <button key={v} type="button" onClick={() => onVcp("60", v)}
                  className={`${tap} border-gray-400 bg-bg-100 text-gray-1000`}>{n}</button>
        ))}
      </div>
    </div>
  );
}

function Panel({ title, hint, children, className = "" }:
  { title: string; hint?: string; children: React.ReactNode; className?: string }) {
  return (
    <section className={`${card} ${className} flex min-h-0 flex-col`}>
      <header className="shrink-0 border-b border-gray-400 px-4 py-3">
        <h2 className={hdr}>{title}</h2>
        {hint && <p className="mt-0.5 text-[clamp(12px,.85vw,16px)] text-gray-900">{hint}</p>}
      </header>
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto px-4 py-3">{children}</div>
    </section>
  );
}

function Btn({ children, onClick, variant = "secondary", className = "", disabled }: {
  children: React.ReactNode; onClick?: () => void | Promise<void>;
  variant?: "primary" | "secondary" | "danger"; className?: string; disabled?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const styles = {
    primary: "bg-gray-1000 text-bg-200 hover:bg-white border-transparent",
    secondary: "bg-bg-100 text-gray-1000 border-gray-400 hover:border-gray-600 hover:bg-gray-100",
    danger: "bg-bg-100 text-red-400 border-gray-400 hover:border-red-400/60 hover:bg-red-500/10",
  }[variant];
  return (
    <button
      type="button" disabled={disabled || busy}
      onClick={async () => { setBusy(true); try { await onClick?.(); } finally { setBusy(false); } }}
      className={`inline-flex items-center justify-center gap-2 rounded-[6px] border px-4 py-2.5
                  text-[clamp(14px,1vw,18px)] font-medium transition-colors disabled:opacity-50 ${styles} ${className}`}
    >
      {busy && (
        <span className="h-3 w-3 animate-spin rounded-full border border-current border-t-transparent" />
      )}
      {children}
    </button>
  );
}

/**
 * True fine brightness. VCP 0x10 is an integer 0-100 with nothing between two
 * steps, so a "fine" slider over a narrow VCP range is just the coarse control
 * with a shorter throw. This scales the display gamma instead: continuous,
 * lands between the graduations, and sends nothing over DDC.
 *
 * The server holds it, because CoreGraphics reverts gamma the moment the
 * process that set it exits.
 */
function FineDim() {
  const [lvl, setLvl] = useState(1);
  const [floor, setFloor] = useState(0.15);
  const pending = useRef<number | null>(null);
  const timer = useRef<number | null>(null);

  useEffect(() => { getDim().then((d) => { setLvl(d.level); setFloor(d.floor); }).catch(() => {}); }, []);

  const push = (v: number) => {
    setLvl(v);                       // optimistic; gamma is instant
    pending.current = v;
    if (timer.current === null) {
      timer.current = window.setTimeout(async () => {
        timer.current = null;
        const target = pending.current;
        if (target !== null) { try { await setDim(target); } catch { /* ignore */ } }
      }, 40);                        // no bus involved, so this can be quick
    }
  };

  return (
    <div className="w-full">
      <div className="flex items-center justify-between text-[11px] text-gray-800">
        <span className="uppercase tracking-wide">fine · software</span>
        <span className="font-mono tabular-nums">{Math.round(lvl * 100)}%</span>
      </div>
      <input
        type="range" className="h" min={floor} max={1} step={0.001} value={lvl}
        style={{ ["--fill" as string]: `${((lvl - floor) / (1 - floor)) * 100}%` }}
        onChange={(e) => push(+e.target.value)}
      />
    </div>
  );
}

/* ------------------------------------------------------------- controls */

/** Tailwind's lg breakpoint, as a hook.
 *  A media query rather than a CSS class because the faders change ORIENTATION,
 *  and a vertical range input needs real height — which portrait simply has not
 *  got. Restyling cannot fix that; the markup has to differ. */
function useIsDesktop() {
  const [wide, setWide] = useState(
    () => typeof window !== "undefined" && window.matchMedia("(min-width: 1024px)").matches);
  useEffect(() => {
    const mq = window.matchMedia("(min-width: 1024px)");
    const on = () => setWide(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return wide;
}

function Fader({ c, v, onChange }: { c: Ctl; v?: Val; onChange: (n: number) => void }) {
  const max = v?.max && v.max > 0 ? v.max : 100;
  const cur = v?.cur ?? 0;
  const desktop = useIsDesktop();

  // Phone: horizontal, with 44px hit targets. The vertical column below needs
  // height to be usable and there is none in portrait.
  if (!desktop) {
    return (
      <div className="py-3">
        <div className="flex items-baseline justify-between gap-3">
          <span className={`${label} uppercase tracking-wide`}>{c.name}</span>
          <span className={`${num} text-[26px] leading-none`}>{v ? cur : "—"}</span>
        </div>
        <div className="mt-2 flex items-center gap-2">
          <button type="button" aria-label={`${c.name} down`}
                  onClick={() => onChange(Math.max(0, cur - 1))}
                  className="h-11 w-11 shrink-0 rounded-[8px] border border-gray-400 text-[20px]
                             leading-none text-gray-900 active:bg-gray-100">−</button>
          <input
            type="range" className="h min-w-0 flex-1" min={0} max={max} value={cur} disabled={!v}
            style={{ ["--fill" as string]: `${(cur / max) * 100}%` }}
            onChange={(e) => onChange(+e.target.value)}
          />
          <button type="button" aria-label={`${c.name} up`}
                  onClick={() => onChange(Math.min(max, cur + 1))}
                  className="h-11 w-11 shrink-0 rounded-[8px] border border-gray-400 text-[20px]
                             leading-none text-gray-900 active:bg-gray-100">+</button>
        </div>
        {c.code === "10" && <div className="mt-2"><FineDim /></div>}
      </div>
    );
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col items-center gap-4">
      <div className={`${num} shrink-0 text-[clamp(44px,4.4vw,104px)] leading-none tracking-tight`}>
        {v ? cur : "—"}
      </div>
      <input
        type="range" className="v min-h-0 flex-1" min={0} max={max} value={cur} disabled={!v}
        // No height:100% here. flex-1 in a COLUMN flex already sizes the main
        // axis; adding height:100% makes the track as tall as the whole column
        // while the value, label and fine-dim footer still claim their own
        // space, so the column overflows and lands on top of whatever follows.
        style={{ ["--fill" as string]: `${(cur / max) * 100}%` }}
        onChange={(e) => onChange(+e.target.value)}
      />
      <div className={`${label} shrink-0 uppercase tracking-wide`}>{c.name}</div>
      <div className="w-full shrink-0">
        {c.code === "10" && <FineDim />}
        <div className="mt-1 flex items-center justify-center gap-1">
          <button type="button" onClick={() => onChange(Math.max(0, cur - 1))}
                  className="h-7 w-7 rounded-[6px] border border-gray-400 text-[15px] leading-none
                             text-gray-900 hover:bg-gray-100 hover:text-gray-1000">-</button>
          <button type="button" onClick={() => onChange(Math.min(max, cur + 1))}
                  className="h-7 w-7 rounded-[6px] border border-gray-400 text-[15px] leading-none
                             text-gray-900 hover:bg-gray-100 hover:text-gray-1000">+</button>
        </div>
      </div>
    </div>
  );
}

function Row({ c, v, onChange }: { c: Ctl; v?: Val; onChange: (n: number) => void }) {
  const max = v?.max && v.max > 0 ? v.max : 100;
  const cur = v?.cur ?? 0;
  return (
    <div className="py-2.5">
      <div className="flex items-center justify-between gap-3">
        <span className={label}>{c.name}</span>
        <span className={`${num} text-[clamp(16px,1.2vw,22px)]`}>{v ? cur : "—"}</span>
      </div>
      <input
        type="range" className="h mt-1" min={0} max={max} value={cur} disabled={!v}
        style={{ ["--fill" as string]: `${(cur / max) * 100}%`,
                 ["--tint" as string]: c.tint ?? "hsl(0 0% 93%)" }}
        onChange={(e) => onChange(+e.target.value)}
      />
    </div>
  );
}

function Segmented({ c, v, onPick, size = "sm" }:
  { c: Ctl; v?: Val; onPick: (n: number) => void; size?: "sm" | "lg" }) {
  return (
    <div className="py-2.5">
      <div className={`${label} mb-1.5`}>{c.name}</div>
      <div className="inline-flex w-full rounded-[6px] border border-gray-400 p-0.5">
        {c.options?.map(([val, text]) => {
          const on = v?.cur === val;
          return (
            <button
              key={val} type="button"
              onClick={() => { if (!c.confirm || confirm(c.confirm)) onPick(val); }}
              className={`flex-1 rounded-[4px] text-[clamp(14px,1vw,18px)] font-medium transition-colors
                ${size === "lg" ? "px-4 py-4 text-[clamp(15px,1.15vw,21px)]" : "px-3 py-2.5"}
                ${on ? "bg-gray-1000 text-bg-200" : "text-gray-900 hover:bg-gray-100 hover:text-gray-1000"}`}
            >
              {text}
            </button>
          );
        })}
      </div>
      {c.note && <p className="mt-1.5 text-[12px] text-gray-800">{c.note}</p>}
    </div>
  );
}

function Info({ c, v }: { c: Ctl; v?: Val }) {
  return (
    <div className="flex items-center justify-between gap-3 py-2">
      <span className={label}>{c.name}</span>
      <span className={`${num} text-[clamp(15px,1.1vw,20px)]`}>
        {fmt(c, v?.cur)}{c.unit && <span className="ml-0.5 text-gray-800">{c.unit}</span>}
      </span>
    </div>
  );
}

function Switch({ on, onChange, label }: { on: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <button
      type="button" role="switch" aria-checked={on} aria-label={label}
      onClick={() => onChange(!on)}
      className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border
                  transition-colors ${on ? "border-transparent bg-blue-700" : "border-gray-400 bg-gray-100"}`}
    >
      <span className={`inline-block h-4 w-4 rounded-full bg-gray-1000 transition-transform
                        ${on ? "translate-x-6" : "translate-x-1"}`} />
    </button>
  );
}

function StartAtLogin() {
  const [s, setS] = useState<AutostartState | null>(null);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { getAutostart().then(setS).catch(() => setErr("unavailable")); }, []);

  return (
    <div className="mt-4 border-t border-gray-400 pt-3">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className={label}>Start at login</div>
          <div className="text-[12px] text-gray-800">
            {err ? err
              : s === null ? "checking…"
              : s.enabled ? "launchd will start the panel at login"
              : "not starting automatically"}
          </div>
        </div>
        <Switch
          label="Start at login"
          on={!!s?.enabled}
          onChange={async (v) => {
            setS((p) => (p ? { ...p, enabled: v } : p));   // optimistic
            try { setS(await setAutostart(v)); }
            catch { setErr("could not change"); }
          }}
        />
      </div>
      {s?.enabled && (
        <p className="mt-2 text-[12px] text-gray-800">
          Turning this off leaves the running server alone — it only affects the next login.
        </p>
      )}
    </div>
  );
}

/* -------------------------------------------------------- auto & cycler */

function AutoBlock() {
  const [s, setS] = useState<AutoState>({ running: false, lux: null, lid: "open" });
  const refresh = useCallback(() => { getAuto().then(setS).catch(() => {}); }, []);

  return (
    <div className="mt-4 border-t border-gray-400 pt-3">
      <div className="flex items-center justify-between">
        <span className={label}>Auto brightness</span>
        <span className={`${num} text-[13px]`}>{s.lux === null ? "—" : `${s.lux} lx`}</span>
      </div>
      <div className="mt-2 flex items-center gap-2">
        <Btn variant={s.running ? "primary" : "secondary"}
             onClick={async () => { await setAuto(true); setTimeout(refresh, 500); }}>
          On
        </Btn>
        <Btn variant={!s.running ? "primary" : "secondary"}
             onClick={async () => { await setAuto(false); setTimeout(refresh, 500); }}>
          Off
        </Btn>
        <Btn className="ml-auto" onClick={refresh}>Read</Btn>
        <span className="text-[12px] text-gray-800">lid {s.lid}</span>
      </div>
      {s.running && s.lid === "closed" && (
        <p className="mt-2 text-[12px] text-gray-800">Sensor is covered — holding.</p>
      )}
    </div>
  );
}

function CycleBlock() {
  const [dwell, setDwell] = useState(20);
  const [st, setSt] = useState({ running: false, dwell: 20, current: null as number | null });
  const refresh = useCallback(() => { getCycle().then(setSt).catch(() => {}); }, []);
  const NAMES: Record<number, string> = { 15: "DisplayPort", 17: "HDMI 1", 18: "HDMI 2" };

  return (
    <div className="mt-4 border-t border-gray-400 pt-3">
      <div className="flex items-center justify-between">
        <span className={label}>Cycle dwell</span>
        <span className="text-[12px] text-gray-800">
          {st.running
            ? <>cycling · {st.dwell}s{st.current ? ` · ${NAMES[st.current] ?? st.current}` : ""}</>
            : "idle"}
        </span>
      </div>
      <div className="mt-2 flex items-center gap-2">
        <div className="flex h-8 items-center rounded-[6px] border border-gray-400 px-2">
          <input
            type="number" min={1} max={300} value={dwell}
            onChange={(e) => setDwell(Math.max(1, Math.min(300, +e.target.value || 20)))}
            className={`${num} w-12 bg-transparent text-right text-[13px] outline-none`}
          />
          <span className="ml-1 text-[12px] text-gray-800">s</span>
        </div>
        <Btn onClick={async () => {
          if (!confirm(`Hold each input for ${dwell}s? This Mac loses the display while another input is selected — recover with: monctl source dp`)) return;
          await setCycle("start", dwell); setTimeout(refresh, 500);
        }}>Start</Btn>
        <Btn variant="danger" onClick={async () => { await setCycle("stop"); setTimeout(refresh, 500); }}>
          Stop
        </Btn>
        <Btn onClick={refresh}>Read</Btn>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ app */

export default function App() {
  const [groups, setGroups] = useState<Group[]>([]);
  const [vals, setVals] = useState<Record<string, Val>>({});
  const [status, setStatus] = useState("connecting");
  const [busy, setBusy] = useState(false);
  const [caps, setCaps] = useState<string | null>(null);
  const touched = useRef<Record<string, number>>({});
  const writeRef = useRef(makeWriter((e) => setStatus(String(e))));

  const apply = (code: string, v: Val) => setVals((p) => ({ ...p, [code]: v }));

  const stream = useCallback(() => {
    setBusy(true); setStatus("reading");
    let n = 0;
    const es = apiEventSource("/api/stream");
    es.addEventListener("value", (e) => {
      const d = JSON.parse((e as MessageEvent).data);
      if (d.ok) apply(d.code, { cur: d.cur, max: d.max });
      setStatus(`reading ${++n}/38`);
    });
    es.addEventListener("done", () => { es.close(); setBusy(false); setStatus("connected"); });
    es.addEventListener("failed", () => { es.close(); setBusy(false); setStatus("probe failed"); });
    // EventSource auto-reconnects; close so end-of-stream is not a reconnect loop.
    es.onerror = () => { es.close(); setBusy(false); };
  }, []);

  useEffect(() => {
    getCatalog().then((g) => { setGroups(g); stream(); })
                .catch(() => setStatus("server unreachable"));
  }, [stream]);

  // No background polling. Every DDC transaction is ~155ms on a single serial
  // bus and the display feels it, so reads happen only when asked for.

  const write = (code: string, value: number) => {
    touched.current[code] = Date.now();
    apply(code, { cur: value, max: vals[code]?.max ?? 100 });
    writeRef.current(code, value);
  };

  const control = (c: Ctl) => {
    const v = vals[c.code];
    switch (c.type) {
      case "fader":  return <Fader key={c.code} c={c} v={v} onChange={(n) => write(c.code, n)} />;
      case "range":  return <Row key={c.code} c={c} v={v} onChange={(n) => write(c.code, n)} />;
      case "select": return <Segmented key={c.code} c={c} v={v} size={c.big ? "lg" : "sm"}
                                       onPick={(n) => write(c.code, n)} />;
      case "action": return (
        <div key={c.code} className="py-1.5">
          <Btn variant={c.danger ? "danger" : "secondary"} className="w-full"
               onClick={async () => { if (!c.confirm || confirm(c.confirm)) await setVcp(c.code, 1); }}>
            {c.name}
          </Btn>
        </div>
      );
      default: return <Info key={c.code} c={c} v={v} />;
    }
  };

  // Controls that live in a "basic" group but do not earn primary-view space.
  // Contrast (0x12) is a set-once calibration value, not something you reach for
  // — unlike brightness and volume, which change all day.
  const DEMOTED = new Set(["12"]);

  const basicRaw = groups.filter((g) => (g as any).tier !== "advanced");
  const basic = basicRaw.map((g) => ({
    ...g, controls: g.controls.filter((c) => !DEMOTED.has(c.code)),
  }));
  const demoted = basicRaw.flatMap((g) => g.controls.filter((c) => DEMOTED.has(c.code)));
  const advanced = groups.filter((g) => (g as any).tier === "advanced");

  // Phone tabs. Colour calibration (Gain/Saturation/Hue) is 18 of the 38
  // controls and nobody calibrates a monitor from a phone, so it gets its own
  // tab rather than a share of the scroll.
  const colourIds = ["gain", "saturation", "hue"];
  const mobColour = advanced.filter((g) => colourIds.includes(g.id));
  const mobSystem = advanced.filter((g) => !colourIds.includes(g.id));
  const [tab, setTab] = useState<"control" | "colour" | "system">("control");
  // Render ONE tree, never both. Hiding the other with `lg:hidden` still mounts
  // it: QuickActions, AutoBlock, StartAtLogin and CycleBlock each ran twice,
  // duplicating their fetches and polling and fighting over the same state.
  // Invisible is not the same as absent.
  const desktop = useIsDesktop();

  return (
    // h-screen + overflow-hidden is right for a 16:9 desktop canvas and fatal on
    // a phone: it pins the document to the viewport and kills page scroll, so
    // everything below the fold is unreachable. Confine it to lg and use dvh,
    // since mobile browser chrome makes 100vh lie.
    // Scope the phone sizing to below lg so the desktop root is byte-identical
    // to what it always was: h-screen + overflow-hidden, no stray min-height.
    <div className="flex w-full flex-col pb-4 max-lg:min-h-[100dvh] max-lg:px-3
                    lg:h-screen lg:overflow-hidden lg:px-5">
      <header className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-gray-400 py-3">
        <h1 className="text-[15px] font-medium text-gray-1000">Acer KG281K</h1>
        <span className="text-[13px] text-gray-800">DDC/CI · DisplayPort AUX</span>
        <div className="ml-auto flex items-center gap-3">
          <span className="flex items-center gap-2 text-[13px] text-gray-900">
            <span className={`h-1.5 w-1.5 rounded-full ${
              busy ? "animate-pulse bg-blue-700" : status === "connected" ? "bg-emerald-500" : "bg-gray-600"
            }`} />
            {status}
          </span>
          <Btn onClick={stream}>Refresh</Btn>
        </div>
      </header>

      {/* ── phone ─────────────────────────────────────────────────────── */}
      {!desktop && (
      <div className="flex flex-col gap-3 pt-3">
        <Remote onVcp={write} onError={(e) => setStatus(String(e))} />

        <div className="flex gap-1 rounded-[8px] border border-gray-400 bg-bg-100 p-1">
          {(["control", "colour", "system"] as const).map((t) => (
            <button key={t} type="button" onClick={() => setTab(t)}
                    className={`h-10 flex-1 rounded-[6px] text-[12px] uppercase tracking-wide
                                ${tab === t ? "bg-gray-1000 text-bg-200" : "text-gray-900"}`}>
              {t}
            </button>
          ))}
        </div>

        {tab === "control" && (
          <>
            <Panel title="Quick actions" hint="Power, input, audio, watcher">
              <QuickActions onVcp={write} onError={(e) => setStatus(String(e))} />
            </Panel>
            {basic.map((g) => (
              <Panel key={g.id} title={g.name} hint={g.blurb}>
                <div className="divide-y divide-gray-400/60">{g.controls.map(control)}</div>
                {g.layout === "faders" && (<><AutoBlock /><StartAtLogin /></>)}
                {g.layout === "input" && <CycleBlock />}
              </Panel>
            ))}
          </>
        )}

        {tab === "colour" && mobColour.map((g) => (
          <Panel key={g.id} title={g.name} hint={g.blurb}>
            <div className="divide-y divide-gray-400/60">{g.controls.map(control)}</div>
          </Panel>
        ))}

        {tab === "system" && (
          <>
            {demoted.length > 0 && (
              <Panel title="Image" hint="Set-once values, moved out of the primary view">
                <div className="divide-y divide-gray-400/60">{demoted.map(control)}</div>
              </Panel>
            )}
            {mobSystem.map((g) => (
              <Panel key={g.id} title={g.name} hint={g.blurb}>
                <div className="divide-y divide-gray-400/60">{g.controls.map(control)}</div>
              </Panel>
            ))}
            <Panel title="Capabilities" hint="Raw DDC/CI string, VCP 0xF3">
              <Btn onClick={async () => setCaps(await getCaps())}>Fetch</Btn>
              {caps && (
                <pre className="mt-3 max-h-56 overflow-auto rounded-[6px] border border-gray-400
                                bg-bg-200 p-3 font-mono text-[12px] break-words whitespace-pre-wrap
                                text-gray-900">{caps}</pre>
              )}
            </Panel>
          </>
        )}
      </div>
      )}

      {/* ── desktop (unchanged) ───────────────────────────────────────── */}
      {desktop && (
      <main className="grid min-h-0 flex-1 grid-cols-1 gap-4 pt-4 lg:grid-cols-12">
        <Panel title="Quick actions"
               hint="Display power, input, audio and the AirPlay watcher"
               className="lg:col-span-4">
          <QuickActions onVcp={write} onError={(e) => setStatus(String(e))} />
        </Panel>
        {basic.map((g) => (
          <Panel key={g.id} title={g.name} hint={g.blurb}
                 className={g.layout === "faders" ? "lg:col-span-4" : "lg:col-span-3"}>
            {g.layout === "faders" ? (
              <div className="flex min-h-0 flex-1 flex-col">
                <div className="flex min-h-0 flex-1 justify-around gap-8 pt-2">
                  {g.controls.filter((c) => c.type === "fader").map(control)}
                </div>
                <div className="mt-5 shrink-0 divide-y divide-gray-400/60 border-t border-gray-400 pt-1">
                  {g.controls.filter((c) => c.type !== "fader").map(control)}
                </div>
                <AutoBlock />
                <StartAtLogin />
              </div>
            ) : (
              <div className="flex min-h-0 flex-1 flex-col gap-5 pt-1">
                {g.controls.map(control)}
              </div>
            )}
            {g.layout === "input" && <CycleBlock />}
          </Panel>
        ))}

        {/* Advanced is a permanent right-hand pane rather than a drawer — on a
            16:9 canvas the space is there, and scrolling it costs no DDC
            traffic because every value already arrived in the load stream. */}
        <section className={`${card} flex min-h-0 flex-col lg:col-span-5`}>
          <header className="shrink-0 border-b border-gray-400 px-4 py-3">
            <h2 className="text-[14px] font-medium text-gray-1000">Advanced</h2>
            <p className="mt-0.5 text-[12px] text-gray-900">
              {advanced.reduce((n, g) => n + g.controls.length, 0) + demoted.length} further controls
            </p>
          </header>
          <div className="grid min-h-0 flex-1 grid-cols-1 gap-x-6 gap-y-1 overflow-y-auto px-4 py-2
                          xl:grid-cols-2">
            {demoted.length > 0 && (
              <div className="py-2">
                <h3 className="text-[13px] font-medium text-gray-1000">Image</h3>
                <p className="mb-1 text-[12px] text-gray-800">
                  Set-once values, moved out of the primary view.
                </p>
                <div className="divide-y divide-gray-400/60">{demoted.map(control)}</div>
              </div>
            )}
            {advanced.map((g) => (
              <div key={g.id} className="py-2">
                <h3 className="text-[13px] font-medium text-gray-1000">{g.name}</h3>
                <p className="mb-1 text-[12px] text-gray-800">{g.blurb}</p>
                <div className="divide-y divide-gray-400/60">{g.controls.map(control)}</div>
              </div>
            ))}
            <div className="py-2">
              <h3 className="text-[13px] font-medium text-gray-1000">Capabilities</h3>
              <p className="mb-2 text-[12px] text-gray-800">Raw DDC/CI string, VCP 0xF3.</p>
              <Btn onClick={async () => setCaps(await getCaps())}>Fetch</Btn>
              {caps && (
                <pre className="mt-3 max-h-56 overflow-auto rounded-[6px] border border-gray-400
                                bg-bg-200 p-3 font-mono text-[12px] leading-relaxed break-words
                                whitespace-pre-wrap text-gray-900">
                  {caps}
                </pre>
              )}
            </div>
          </div>
        </section>
      </main>
      )}

      <footer className="shrink-0 border-t border-gray-400 pt-2 mt-3 text-[12px] text-gray-800">
        Local only · 127.0.0.1 · readings stream as the bus yields them · CLI{" "}
        <span className="font-mono text-gray-900">monctl</span>
      </footer>
    </div>
  );
}
