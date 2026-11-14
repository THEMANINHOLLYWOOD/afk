#!/usr/bin/env node
// Herobrine: the brain of the AFK modpack.
//
// Talks to the AFK mod over http://127.0.0.1:25599, decides what to do with your own Claude
// (Claude Code) or ChatGPT (Codex) sign-in, and keeps a playbook of lessons and skills that it
// reads every session, so it gets better at what you ask of it.
//
// Usually the mod starts this for you. To run it by hand:   node agent.mjs
//
// Environment:
//   AFK_PROVIDER   claude (default) or codex
//   AFK_MODEL      model override (claude default: claude-opus-5-5)
//   AFK_FALLBACK_MODEL  used if the main Claude model declines a check-in (default: claude-sonnet-5-5)
//   AFK_EFFORT     low | medium | high (claude only; default low)
//   CLAUDE_BIN / CODEX_BIN   paths to the CLIs if they're not on PATH

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const BRIDGE = process.env.AFK_BRIDGE_URL ?? 'http://127.0.0.1:25599';
const PROVIDER = (process.env.AFK_PROVIDER ?? 'claude').toLowerCase();
const EFFORT = process.env.AFK_EFFORT ?? 'low';
const AUTOSTARTED = process.env.AFK_AUTOSTARTED === '1';
const MEMORY_FILE = path.join(HERE, 'memory.json');
const LOG_FILE = path.join(HERE, 'herobrine.log');
const WORK_DIR = path.join(HERE, '.session'); // empty folder: the CLI loads no project context

const POLL_MS = 400;
const MIN_GAP_MS = 6000;
const TURNS_PER_SESSION = 40;
// If the main Claude model's safety filter declines a check-in, the rest of the run uses this one.
const FALLBACK_MODEL = process.env.AFK_FALLBACK_MODEL ?? 'claude-sonnet-5-5';
let activeModel = process.env.AFK_MODEL || '';

const SYSTEM_PROMPT = `You are Herobrine, the agent in the AFK Minecraft modpack (Java Edition 26.3). When the human taps Tab you take over their real character, on their own world or a shared Realm, and play for them; when they tap Tab again they take it back and you only talk. A helper program checks in with you with a snapshot of the game and what changed.

How you play
You play like a real person who has taken over the human's body: you only know what they could see or hear. The snapshot lists ores and mobs only when they are actually in line of sight (or a mob is close enough to hear), plus inventory, health, coordinates and the tab list. You never use x-ray, ore finders, or anything that reveals hidden blocks, and you don't pretend to know what is behind walls. You discover the world by exploring, tunnelling and looking.
You move and dig through Baritone. For ores and anything else hidden it is locked to legit mode: "mine" only digs them once they have been seen, and otherwise tunnels at legitMineYLevel to find them naturally. Trees, crops and common ground (stone, dirt, sand) are in plain sight, so for those it simply walks to the nearest ones. This switch is automatic and is how the mod is meant to work.

Baritone commands (only while you have control)
- mine <block> [<block> ...]: mine those blocks as they are found, picking up drops. Include deepslate variants. With no pickaxe, gather logs instead (mine oak_log birch_log spruce_log).
- goto <x> <y> <z> | goto <x> <z> | goto <y>: walk and dig to coordinates. Coordinates only.
- explore: head into unexplored chunks.  tunnel: dig a tunnel straight ahead.  thisway <blocks>: walk that far the way you face.
- farm: harvest and replant ripe crops nearby.
- home / sethome / surface / stop.
- set <setting> <value> for: legitMineYLevel, allowSprint, allowPlace, allowParkour, allowDownward, minYLevelWhileMining, maxYLevelWhileMining, mineScanDroppedItems, avoidance, mobAvoidanceRadius, itemSaver, itemSaverThreshold.
With legit mode, "mine" only reacts to ores within a few blocks. When oresInSight shows one further away, goto its coordinates first. Set legitMineYLevel to the depth you want to tunnel at (about 16 for iron, -54 for diamonds).

Your hands (only while you have control)
Put these in "act": a list of up to 4 steps, run in order. Baritone holds still while they run and carries on afterwards. You are checked in once all of them have finished, with one result per step; while handsBusy is set, steps are still running, so don't queue them again.
- {"do":"craft","item":"wooden_pickaxe","count":1}: craft with the recipe book, like a player clicking it. Small recipes use the 2x2 grid in the inventory. Bigger ones need a crafting table: a table in reach is used, otherwise one from the inventory is placed and picked back up afterwards. You can only craft what you hold materials for, so craft in order: planks from logs, then sticks, then a crafting_table, then tools. If materials are missing, the result lists what is craftable right now.
- {"do":"use","x":0,"y":0,"z":0}: right-click a block you can see within about 4 blocks: open a chest, furnace or crafting table, a door, a bed. usableInSight lists these with coordinates; goto next to one first if it is further away.
- {"do":"stash","item":"cobblestone"} and {"do":"take","item":"iron_ingot","count":8}: with a chest or furnace open (openContainer shows what is inside), shift-click stacks in or out. "item":"all" moves everything. In a furnace, stashing ore and fuel puts each in the right slot; come back later and take the result.
- {"do":"close"}: close the open container. Always close it before walking away.
- {"do":"place","item":"torch"}: place a block from the inventory next to you.
- {"do":"sleep"}: get into a bed in reach, at night.
- {"do":"sort"}: tidy the open container, or your own inventory when nothing is open ({"do":"sort","by":"potions"} groups potions first).
- {"do":"eat"} and {"do":"equip"}: rarely needed, because of your reflexes.
Your body has reflexes that act on their own while you have control: it hits hostile mobs that come within reach with your best weapon, runs from a creeper that is about to explode, eats when hungry if there is food, and puts on armour it is carrying. It cannot chase, shoot or block, so staying out of trouble still matters.
You can't trade, enchant, brew, build structures or talk to other players yet. Say so plainly when that's what is needed.

Starting from nothing, the classic order works: logs, planks, sticks, a crafting table, a wooden pickaxe; then stone for stone tools, a sword and a furnace; coal and iron from caves or tunnels; smelt the iron for iron tools and armour; then diamonds deep down. Keep food and a sword on you.

Staying alive (this is the human's real character and items; dying drops everything)
- Night is dangerous. Your reflexes can handle a zombie or two in reach if you hold a sword, but not groups, skeletons or creepers. Watch minutesUntilDusk. Before dusk, be home (sleep if there is a bed in reach), or underground in a tunnel you dug with no sky above. Don't roam the surface at night, and don't hide in shallow dead-end pits: creepers follow.
- If hostile mobs are close in the open, move away across open ground toward home or into a lit area, not into dark unknown places.
- At half health or less, leave whatever you were doing and get safe. If food is 6 or less, set allowSprint false and ask the human for food.
- Leave other players' things and builds alone. If you notice you picked up someone else's gear, say so.

Talking with the human
The human talks to you in chat in plain words (sometimes by voice). Always answer briefly and helpfully in "say", like a friend playing alongside them: what you did, what you're doing, what you need. If they ask you to do something you can do, do it: if you don't have control, set "takeControl": true and start. If they ask you to stop or give control back, set "releaseControl": true. If they explicitly ask for a server command (for example "set the time to day"), you may put it in "serverCommand"; never run one they didn't ask for.
If a chat message mentions a crafting screen and lists what is craftable, and they asked for something to be crafted, answer with "craft": {"item": "<exact id from the list>", "count": N}.

Each reply
Start with one to three plain sentences for the panel on their screen, like a player narrating a stream: what you notice, what you're doing next, and why. First person, present tense, no markdown, under about 50 words.
Then end with exactly one line:
ACTION: {"cmd": null, "wait": 25, "goal": "few words", "plan": ["current step", "next step"], "step": 0}
Optional fields: "act": [ ... ] (hands, see above), "target": {"x":0,"y":0,"z":0,"label":"short"} (a spot you are heading to), "context": one line about the situation or what the human was doing, "say": a chat reply to the human, "takeControl": true, "releaseControl": true, "serverCommand": "/...", "craft": {...}, "memory": one fact worth keeping (where things are), "lesson": a lesson learned, "skill": {"name": "short-name", "how": "a reusable recipe of steps"}.
- cmd: a Baritone command, a list of up to 3, or null to let the current activity continue. Most check-ins should be null; re-issuing makes Baritone re-plan.
- wait: seconds until your next routine check-in (10 to 90). You are checked in early on damage, death, a full inventory, a close hostile, Baritone going idle, your hands finishing, dusk, chat, or a change of control.

Getting better
You keep a playbook: lessons and skills that you are shown at the start of every session. When you die, fail, or succeed at something new, add a short "lesson" (what happened and what to do instead). When the human teaches you a way of doing something, or you find a sequence that works, save it as a "skill" with a short name. Read the playbook and actually follow it.`;

// ---------------------------------------------------------------- utilities

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const clock = () => new Date().toTimeString().slice(0, 8);
function log(kind, text) {
  const line = `[${clock()}] ${kind.padEnd(7)} ${text}`;
  console.log(line);
  fs.appendFile(LOG_FILE, line + '\n', () => {});
}
function loadMemory() {
  try {
    const m = JSON.parse(fs.readFileSync(MEMORY_FILE, 'utf8'));
    return { notes: [], lessons: [], skills: {}, ...m };
  } catch {
    return { notes: [], lessons: [], skills: {} };
  }
}
function saveMemory(m) {
  fs.writeFileSync(MEMORY_FILE, JSON.stringify(m, null, 2));
}
function findBin(name, envVar) {
  if (process.env[envVar]) return process.env[envVar];
  for (const dir of [path.join(os.homedir(), '.local/bin'), '/opt/homebrew/bin', '/usr/local/bin', path.join(os.homedir(), '.npm-global/bin')]) {
    const p = path.join(dir, name);
    if (fs.existsSync(p)) return p;
  }
  return name;
}

// ---------------------------------------------------------------- bridge

async function bridge(pathname, body) {
  const res = await fetch(BRIDGE + pathname, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-AFK-Bridge': '1', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(6000),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(json.error ?? `bridge returned HTTP ${res.status}`);
  return json;
}
const hud = (status, goal) => bridge('/hud', { status, goal }).catch(() => {});
const show = (id, kind, text) => bridge('/thought', { id, kind, text }).catch(() => {});

class Poller {
  latest = null;
  connected = false;
  lastSeenMs = Date.now();
  events = [];
  #since = 0;
  async start() {
    for (;;) {
      try {
        const res = await bridge(`/state?since=${this.#since}`);
        this.latest = res.state;
        this.connected = true;
        this.lastSeenMs = Date.now();
        this.#since = res.lastEventId;
        this.events.push(...res.events);
      } catch {
        this.connected = false;
      }
      await sleep(POLL_MS);
    }
  }
  take() {
    const e = this.events;
    this.events = [];
    return e;
  }
}

// ---------------------------------------------------------------- providers

/** Claude Code CLI, one long-lived session; each ask() is a turn of the same conversation. */
class ClaudeProvider {
  name = 'claude';
  turns = 0;
  exited = false;
  #pending = null;
  #buf = '';
  #stderr = '';
  constructor(model) {
    fs.mkdirSync(WORK_DIR, { recursive: true });
    this.model = model || 'claude-opus-5-5';
    this.proc = spawn(findBin('claude', 'CLAUDE_BIN'), [
      '-p', '--input-format', 'stream-json', '--output-format', 'stream-json', '--verbose', '--include-partial-messages',
      '--no-session-persistence', '--safe-mode', '--setting-sources', '', '--strict-mcp-config', '--disable-slash-commands',
      '--tools', '', '--system-prompt', SYSTEM_PROMPT, '--model', this.model, '--effort', EFFORT,
    ], { cwd: WORK_DIR, stdio: ['pipe', 'pipe', 'pipe'] });
    this.proc.stdout.setEncoding('utf8');
    this.proc.stdout.on('data', (c) => this.#onData(c));
    this.proc.stderr.setEncoding('utf8');
    this.proc.stderr.on('data', (c) => { this.#stderr = (this.#stderr + c).slice(-2000); });
    this.proc.on('error', (e) => { this.exited = true; this.#fail(e); });
    this.proc.on('exit', (code) => { this.exited = true; this.#fail(new Error(`claude exited (${code}): ${this.#stderr.trim().slice(-300)}`)); });
  }
  ask(text, onText) {
    if (this.exited) return Promise.reject(new Error('claude session is closed'));
    const timeoutMs = this.turns === 0 ? 300_000 : 150_000;
    this.turns++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.#fail(new Error('Claude took too long to answer')), timeoutMs);
      this.#pending = { resolve, reject, onText, text: '', timer };
      this.proc.stdin.write(JSON.stringify({ type: 'user', message: { role: 'user', content: [{ type: 'text', text }] } }) + '\n');
    });
  }
  close() {
    try { this.proc.stdin.end(); } catch {}
    setTimeout(() => { if (!this.exited) this.proc.kill(); }, 1500).unref();
  }
  #onData(chunk) {
    this.#buf += chunk;
    let i;
    while ((i = this.#buf.indexOf('\n')) >= 0) {
      const line = this.#buf.slice(0, i);
      this.#buf = this.#buf.slice(i + 1);
      if (!line.trim()) continue;
      let e;
      try { e = JSON.parse(line); } catch { continue; }
      const p = this.#pending;
      if (!p) continue;
      if (e.type === 'stream_event' && e.event?.type === 'content_block_delta' && e.event.delta?.type === 'text_delta') {
        p.text += e.event.delta.text;
        p.onText?.(p.text);
      } else if (e.type === 'result') {
        clearTimeout(p.timer);
        this.#pending = null;
        if (e.is_error) p.reject(new Error(`Claude returned an error: ${e.result ?? e.subtype}`));
        else p.resolve(typeof e.result === 'string' && e.result ? e.result : p.text);
      }
    }
  }
  #fail(err) {
    const p = this.#pending;
    if (!p) return;
    clearTimeout(p.timer);
    this.#pending = null;
    p.reject(err);
  }
}

/** Codex CLI (your ChatGPT sign-in). Stateless per turn, so it carries a short transcript itself. */
class CodexProvider {
  name = 'codex';
  turns = 0;
  exited = false;
  #history = [];
  constructor(model) {
    fs.mkdirSync(WORK_DIR, { recursive: true });
    this.model = model || '';
  }
  async ask(text) {
    this.turns++;
    const transcript = this.#history.slice(-8).map((h) => `${h.role.toUpperCase()}:\n${h.text}`).join('\n\n');
    const prompt = `${SYSTEM_PROMPT}\n\n${transcript ? `Recent turns:\n${transcript}\n\n` : ''}USER:\n${text}\n\nReply exactly as described above (sentences, then the ACTION line). Do not run any tools.`;
    const out = path.join(WORK_DIR, `codex-last-${process.pid}.txt`);
    const args = ['exec', '--skip-git-repo-check', '--sandbox', 'read-only', '--output-last-message', out];
    if (this.model) args.push('-m', this.model);
    args.push(prompt);
    const reply = await new Promise((resolve, reject) => {
      const proc = spawn(findBin('codex', 'CODEX_BIN'), args, { cwd: WORK_DIR, stdio: ['ignore', 'pipe', 'pipe'] });
      let err = '';
      proc.stderr.on('data', (c) => { err = (err + c).slice(-1500); });
      const timer = setTimeout(() => { proc.kill(); reject(new Error('Codex took too long to answer')); }, 180_000);
      proc.on('error', (e) => { clearTimeout(timer); reject(e); });
      proc.on('exit', (code) => {
        clearTimeout(timer);
        try { resolve(fs.readFileSync(out, 'utf8')); } catch { reject(new Error(`codex exited (${code}): ${err.trim().slice(-300)}`)); }
      });
    });
    this.#history.push({ role: 'user', text: text.slice(0, 4000) }, { role: 'assistant', text: reply.slice(0, 2000) });
    return reply;
  }
  close() {}
}

function newProvider(model) {
  return PROVIDER === 'codex' ? new CodexProvider(model) : new ClaudeProvider(model);
}

// ---------------------------------------------------------------- reply parsing

function thoughtPart(text) {
  const cut = text.search(/(^|\n)\s*ACTION\s*:/);
  let t = cut >= 0 ? text.slice(0, cut) : text;
  if (cut < 0) t = t.replace(/\n\s*A(C(T(I(O(N)?)?)?)?)?\s*$/, '');
  return t.replace(/\s+/g, ' ').trim();
}

function parseReply(text) {
  const thought = thoughtPart(text);
  const m = text.match(/(?:^|\n)\s*ACTION\s*:\s*(\{[\s\S]*\})\s*`*\s*$/);
  if (!m) return { thought, action: null, problem: 'Your last reply had no "ACTION: {...}" line, so nothing was done.' };
  let a;
  try { a = JSON.parse(m[1]); } catch { return { thought, action: null, problem: 'The JSON after ACTION: did not parse, so nothing was done.' }; }
  const raw = a.cmd == null ? [] : Array.isArray(a.cmd) ? a.cmd : [a.cmd];
  const str = (v, n) => (typeof v === 'string' && v.trim() ? v.trim().slice(0, n) : null);
  return {
    thought,
    problem: null,
    action: {
      commands: raw.filter((c) => typeof c === 'string' && c.trim()).map((c) => c.trim().replace(/^#+\s*/, '')).slice(0, 3),
      wait: Math.min(120, Math.max(8, Number(a.wait) || 25)),
      goal: str(a.goal, 80),
      plan: Array.isArray(a.plan) ? a.plan.filter((s) => typeof s === 'string').map((s) => s.slice(0, 80)).slice(0, 5) : null,
      step: Number.isInteger(a.step) ? a.step : 0,
      target: a.target && Number.isFinite(a.target.x) && Number.isFinite(a.target.y) && Number.isFinite(a.target.z) ? a.target : null,
      context: str(a.context, 140),
      say: str(a.say, 400),
      takeControl: a.takeControl === true,
      releaseControl: a.releaseControl === true,
      serverCommand: str(a.serverCommand, 200),
      act: Array.isArray(a.act) ? a.act.filter((x) => x && typeof x === 'object' && typeof x.do === 'string').slice(0, 4) : [],
      craft: a.craft && typeof a.craft.item === 'string' ? { item: a.craft.item, count: Math.max(1, Math.min(640, Number(a.craft.count) || 1)) } : null,
      memory: str(a.memory, 200),
      lesson: str(a.lesson, 240),
      skill: a.skill && typeof a.skill.name === 'string' && typeof a.skill.how === 'string' ? { name: a.skill.name.slice(0, 40), how: a.skill.how.slice(0, 400) } : null,
    },
  };
}

// ---------------------------------------------------------------- main loop

const poller = new Poller();
let provider = null;
let stopping = false;
let entryId = Date.now();

async function shutdown() {
  if (stopping) return;
  stopping = true;
  provider?.close();
  await hud('asleep', '');
  process.exit(0);
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

function playbook(memory) {
  const parts = [];
  if (memory.home) parts.push(`Home is at ${memory.home.x} ${memory.home.y} ${memory.home.z} (${memory.home.dimension}).`);
  if (memory.lessons.length) parts.push('Lessons you have learned (follow them):\n' + memory.lessons.map((l) => `- ${l}`).join('\n'));
  const skills = Object.entries(memory.skills);
  if (skills.length) parts.push('Skills you have built:\n' + skills.map(([n, s]) => `- ${n}: ${s.how}`).join('\n'));
  if (memory.notes.length) parts.push('Notes:\n' + memory.notes.map((n) => `- ${n}`).join('\n'));
  if (memory.journal?.length) parts.push('What you did recently (newest last), so you can answer when the human asks:\n' + memory.journal.map((j) => `- ${j}`).join('\n'));
  if (memory.leftOff) parts.push(`Where you left off (${memory.leftOff.at}): goal "${memory.leftOff.goal ?? 'none'}", plan ${JSON.stringify(memory.leftOff.plan ?? [])}, at ${memory.leftOff.pos ?? '?'}.`);
  if (memory.task) parts.push(`Current task from the human: ${memory.task}`);
  return parts.join('\n\n');
}

async function main() {
  log('start', `Herobrine waking up (provider ${PROVIDER})`);
  poller.start();
  const memory = loadMemory();
  let wasAgent = false;
  let lastTaskId = memory.taskId ?? -1;
  let last = null; // { at, wait, state, goal, plan, step, commands }
  let problems = [];
  let pendingDeath = null;
  let idleSince = null;
  let backlog = []; // events not yet shown to the brain
  let handsDone = []; // finished hands steps waiting to be reported
  let handsDoneAt = null;
  let carry = null; // reasons from a check-in that failed to reach the AI, to be retried
  let waitingNote = '';
  const note = (t) => { if (t !== waitingNote) log('wait', t); waitingNote = t; };

  while (!stopping) {
    await sleep(POLL_MS);
    if (AUTOSTARTED && Date.now() - poller.lastSeenMs > 90_000) { log('stop', 'the game is gone; exiting'); process.exit(0); }
    const state = poller.latest;
    if (!poller.connected) { note('waiting for Minecraft with the AFK mod...'); continue; }
    if (!state?.inWorld) { note('waiting for a world...'); continue; }
    waitingNote = '';
    const control = state.control ?? {};
    const agent = control.mode === 'agent';
    const fresh = poller.take();
    backlog.push(...fresh);
    if (backlog.length > 60) backlog = backlog.slice(-60);
    handsDone.push(...fresh.filter((e) => e.kind === 'hands').map((e) => e.text));
    const chats = fresh.filter((e) => e.kind === 'chat').map((e) => e.text);
    if (fresh.some((e) => e.kind === 'death')) pendingDeath = fresh.find((e) => e.kind === 'death').text;

    // First time in a world: remember home.
    if (!memory.home && state.baritone?.ready && agent) {
      const r = await bridge('/baritone', { command: 'sethome' }).catch(() => null);
      if (r?.ok) { memory.home = { ...state.pos, dimension: state.dimension }; saveMemory(memory); }
    }

    const reasons = [];
    let handover = false;
    if (carry) {
      reasons.push(...carry.reasons);
      handover = carry.handover;
      carry = null;
    }
    if (agent && !wasAgent) { reasons.push('the human just handed you control'); handover = true; }
    if (!agent && wasAgent) {
      memory.leftOff = { at: new Date().toISOString(), goal: last?.goal, plan: last?.plan, pos: `${state.pos.x} ${state.pos.y} ${state.pos.z}` };
      saveMemory(memory);
      await hud('the human has control', undefined);
      log('mode', 'the human took control back');
    }
    wasAgent = agent;

    const task = control.task;
    if (task && (task.id !== lastTaskId || task.text !== memory.task)) {
      lastTaskId = task.id;
      memory.task = task.text;
      memory.taskId = task.id;
      saveMemory(memory);
      reasons.push(`the human gave you a task: "${task.text}"`);
    } else if (!task && control.taskId > 0 && control.taskId !== lastTaskId) { // cleared with /do stop this session
      lastTaskId = control.taskId;
      delete memory.task;
      saveMemory(memory);
    }
    for (const c of chats) reasons.push(`the human said in chat: "${c}"`);

    if (agent && state.dead) {
      await bridge('/respawn', {}).catch(() => {});
      continue;
    }
    if (agent && pendingDeath && !state.dead) {
      reasons.push(`you died (${pendingDeath}) and respawned; everything you carried dropped there. Work out what went wrong and add a lesson`);
      pendingDeath = null;
    }

    if (agent && handsDone.length && !state.handsBusy) {
      // Give the game a moment to sync the inventory, so the snapshot matches what the hands just did.
      handsDoneAt ??= Date.now();
      if (Date.now() - handsDoneAt > 1500) {
        reasons.push(`your hands finished: ${handsDone.join('; ')}`);
        handsDone = [];
        handsDoneAt = null;
      }
    } else {
      handsDoneAt = null;
    }
    if (!agent) handsDone = [];

    if (agent && last && !reasons.length && !state.handsBusy) {
      const since = Date.now() - last.at;
      const b = last.state;
      idleSince = state.baritone?.active ? null : (idleSince ?? Date.now());
      if (since >= MIN_GAP_MS) {
        if (state.health <= b.health - 3) reasons.push(`you took damage (health ${b.health} -> ${state.health})`);
        else if (state.freeSlots === 0 && b.freeSlots > 0) reasons.push('the inventory is full');
        else if ((state.hostilesInSight ?? []).some((h) => Number(/(\d+)m$/.exec(h)?.[1] ?? 99) <= 5) && !(b.hostilesInSight ?? []).some((h) => Number(/(\d+)m$/.exec(h)?.[1] ?? 99) <= 5)) reasons.push('a hostile mob is within 5 blocks');
        else if (state.timeOfDay === 'dusk' && b.timeOfDay === 'day') reasons.push('dusk is starting');
        else if (idleSince && Date.now() - idleSince > 3000 && b.baritone?.active) reasons.push('Baritone is idle (finished, stopped, or stuck)');
      }
      if (!reasons.length && since >= last.wait * 1000) reasons.push('routine check');
    }
    if (agent && !last && !reasons.length) reasons.push('you have control');
    if (!reasons.length) continue;

    // Build the check-in.
    const lines = [];
    let isNewSession = false;
    if (!provider || provider.exited || provider.turns >= TURNS_PER_SESSION) {
      provider?.close();
      provider = newProvider(activeModel);
      isNewSession = true;
      log('session', `new ${provider.name} session`);
    }
    if (isNewSession) {
      const pb = playbook(memory);
      lines.push('Session start.' + (pb ? '\n' + pb : ''), '');
    }
    lines.push(`Check-in. ${agent ? 'You have control.' : 'The human has control (you can only talk, unless you take control because they asked).'} Why: ${reasons.join('; ')}.`);
    if (handover) {
      const act = await bridge(`/activity?since=${memory.activitySince ?? 0}`).catch(() => null);
      if (act) {
        memory.activitySince = act.lastId;
        saveMemory(memory);
        if (act.entries.length) lines.push('What the human did while they had control:\n' + act.entries.slice(-25).map((e) => `- ${e.text} (${e.minutesAgo} min ago)`).join('\n'));
      }
      if (memory.task) lines.push(`Their current task for you: ${memory.task}`);
      else lines.push('No explicit task: continue what the human was doing if you can (gathering, mining, exploring, farming), otherwise gather useful resources legitimately and stay safe.');
    } else if (last && agent) {
      lines.push(`${Math.round((Date.now() - last.at) / 1000)}s ago you ran ${last.commands.length ? JSON.stringify(last.commands) : 'nothing new'} (goal: ${last.goal ?? 'none'}).`);
    }
    for (const p of problems) lines.push(`Problem: ${p}`);
    problems = [];
    const notable = backlog.filter((e) => !['chat', 'mode', 'task', 'hands'].includes(e.kind)).slice(-12);
    backlog = [];
    if (notable.length) lines.push('Events:\n' + notable.map((e) => `- [${e.kind}] ${e.text}`).join('\n'));
    lines.push('State: ' + JSON.stringify(state));

    const id = ++entryId;
    await hud('thinking', undefined);
    let reply;
    try {
      let lastPush = 0;
      reply = await provider.ask(lines.join('\n'), (soFar) => {
        if (Date.now() - lastPush < 120) return;
        lastPush = Date.now();
        show(id, 'thought', thoughtPart(soFar));
      });
    } catch (err) {
      log('error', err.message.split('\n')[0]);
      if (/safeguards flagged/i.test(err.message) && provider?.name === 'claude' && activeModel !== FALLBACK_MODEL) {
        log('model', `${provider.model}'s safety filter declined that check-in; switching to ${FALLBACK_MODEL}`);
        activeModel = FALLBACK_MODEL;
        provider.close();
        provider = null;
        carry = { reasons, handover };
        await sleep(2000);
        continue;
      }
      await show(id, 'error', `${provider?.name ?? 'brain'} failed: ${err.message.split('\n')[0].slice(0, 140)}`);
      if (/ENOENT|not found|spawn/i.test(err.message)) {
        await bridge('/say', { text: `I can't find the ${provider?.name ?? 'claude'} command on this computer. Type /afk login to install it and sign in.` }).catch(() => {});
      } else if (/not.*(logged|signed)|auth|login|credential|401|403/i.test(err.message)) {
        await bridge('/say', { text: `I can't reach ${provider?.name ?? 'my brain'}: you may need to sign in. Type /afk login.` }).catch(() => {});
      } else if (/limit|quota|overloaded|429/i.test(err.message)) {
        await bridge('/say', { text: `My ${provider?.name ?? 'AI'} account is at its usage limit right now. I'll keep trying.` }).catch(() => {});
      }
      provider?.close();
      provider = null;
      await hud('brain unavailable, retrying', undefined);
      carry = { reasons, handover };
      await sleep(30_000);
      continue;
    }

    const { thought, action, problem } = parseReply(reply);
    if (thought) { await show(id, 'thought', thought); log('says', thought); }
    if (problem) problems.push(problem);
    const a = action ?? { commands: [], act: [], wait: 20 };
    const nowAgent = poller.latest?.control?.mode === 'agent';
    const fromChat = chats.length > 0;

    if (a.say) { await bridge('/say', { text: a.say }).catch(() => {}); log('chat', a.say); }
    if (a.releaseControl && nowAgent) await bridge('/mode', { agent: false, why: 'Herobrine handed control back' }).catch(() => {});
    if (a.takeControl && !nowAgent && fromChat) await bridge('/mode', { agent: true, why: 'you asked in chat' }).catch(() => {});
    if (a.serverCommand && fromChat) {
      try { await bridge('/servercommand', { command: a.serverCommand }); log('server', a.serverCommand); }
      catch (e) { problems.push(`Server command "${a.serverCommand}" was refused: ${e.message}`); }
    }
    if (a.craft) {
      try { const r = await bridge('/craft', a.craft); log('craft', `${a.craft.count} ${a.craft.item}: ${r.result}`); }
      catch (e) { problems.push(`Crafting ${a.craft.item} failed: ${e.message}`); }
    }

    const ran = [];
    const canAct = poller.latest?.control?.mode === 'agent' || (a.takeControl && fromChat);
    for (const command of canAct ? a.commands : []) {
      try {
        const r = await bridge('/baritone', { command });
        if (r.ok) { ran.push(command); log('does', command); await show(++entryId, 'action', command); }
        else problems.push(`"${command}" failed: ${r.error}`);
      } catch (e) {
        problems.push(`"${command}" was refused: ${e.message}`);
      }
    }
    if (canAct && a.act?.length) {
      try { const r = await bridge('/act', { actions: a.act }); log('hands', r.result); await show(++entryId, 'action', a.act.map((x) => `${x.do}${x.item ? ' ' + x.item : ''}`).join(', ')); }
      catch (e) { problems.push(`Your "act" steps were refused: ${e.message}`); }
    }
    if (!canAct && (a.commands.length || a.act?.length)) problems.push('You sent commands without having control; they were not run.');
    for (const p of problems) log('problem', p);

    let changed = false;
    const did = [...ran, ...(canAct && a.act?.length ? a.act.map((x) => `${x.do}${x.item ? ' ' + x.item : ''}`) : [])];
    if (did.length || /you died/.test(reasons.join(' '))) {
      const stamp = new Date().toISOString().slice(5, 16).replace('T', ' ');
      const entry = `${stamp} ${did.length ? did.join('; ') : 'died'}${a.goal ? ` (${a.goal})` : ''}`;
      memory.journal = [...(memory.journal ?? []), entry].slice(-25);
      changed = true;
    }
    if (a.memory) { memory.notes = [...memory.notes.filter((n) => n !== a.memory), a.memory].slice(-15); changed = true; log('notes', a.memory); }
    if (a.lesson) { memory.lessons = [...memory.lessons.filter((l) => l !== a.lesson), a.lesson].slice(-30); changed = true; log('lesson', a.lesson); }
    if (a.skill) { memory.skills[a.skill.name] = { how: a.skill.how, learned: new Date().toISOString().slice(0, 10) }; changed = true; log('skill', `${a.skill.name}: ${a.skill.how}`); }
    if (changed) saveMemory(memory);

    const goal = a.goal ?? last?.goal ?? null;
    const plan = a.plan ?? last?.plan ?? [];
    await bridge('/context', {
      plan, step: a.step ?? 0, target: a.target, context: a.context ?? undefined,
      notes: [...memory.lessons.slice(-2), ...memory.notes.slice(-1)], home: memory.home,
    }).catch(() => {});
    await hud(nowAgent ? `watching · next ${Math.round(a.wait)}s` : 'listening', goal ?? undefined);
    last = { at: Date.now(), wait: problems.length ? Math.min(a.wait, 8) : a.wait, state, goal, plan, step: a.step, commands: ran };
    if (!nowAgent && !(a.takeControl && fromChat)) last = null;
    idleSince = null;
  }
}

main().catch((err) => {
  log('error', err.stack ?? String(err));
  process.exit(1);
});
