# Design direction (2026-10-01)

Source: impeccable critique (dual assessment) of v7.5.0. Score 25/40: competent, not premium. The owner wants the app
to feel alive and premium, and to compare two directions side by side.

## Two switchable styles (Settings › Appearance › Style), each with a dark and a light variant

### A. Mission Console (bold)
A calm control-room instrument, not a chat toy.
- Ground: near-black blue-graphite `#0B0E13` (dark) / warm paper `#F4F1EA` (light). Four surface tiers, progressively
  lighter; cards have NO stroke, a top-lit gradient (surface +6% lightness at top) and a 1dp inner top highlight
  (white ~6% alpha on dark); real elevation with accent-tinted shadows on API 28+.
- Signal colours carry meaning only: teal `#3DDBB0` alive/ok, amber `#F2B544` watch, coral `#FF6B6B` critical. One
  interaction accent: electric blue `#6EA8FF` (primary action + the user's own message only).
- Type: Space Grotesk (500/600, tight tracking, headings and body) + JetBrains Mono with tabular numerals for every
  number, id, IP, model name, path and code. Scale 11 label (caps, +0.08 tracking) / 14 body / 17 title / 28 metric /
  40 hero; 700 weight only for the single hero.
- Shape: 14dp radius cards, 10dp controls. Icons: monochrome line set (1.5dp round-cap), tinted only when active or
  carrying status. No rainbow icon tiles.
- Motion signature "pulse": a heartbeat ring from the status dot on every real server poll (600ms, scale 1→2.2,
  alpha .5→0), stalls/turns amber after missed polls; a 2dp sweep on the composer's top edge while Hermes streams;
  gauges sweep in 600ms PathInterpolator(0.2,0,0,1); digits roll per column (450ms) when values change; press 0.97 /
  overshoot release. Nothing moves unless state moves. Honour animator scale.

### B. Refined (calm)
The current graphite/blue identity, done properly.
- Same palette family as today (graphite `#101214`, blue `#5a9cf4`), light variant = today's Paper refined.
- Type: Roboto kept for words, JetBrains Mono tabular numerals for numbers and ids; same type scale as A.
- Depth tiers instead of 1dp strokes everywhere (subtle 1dp stroke allowed on interactive controls only).
- Icon tiles: single muted tint (no rainbow), status colour only when meaningful.
- Motion: the existing entrance/shimmer system, but meaningful (heartbeat on poll, digit roll), no idle loops.

## Retired looks
Graphite, Paper, Midnight, Terminal, Dynamic are replaced by: Console Dark, Console Light, Refined Dark, Refined Light.
Stored old ids map to the nearest new one (graphite/midnight/terminal/dynamic → refined dark, paper → refined light).

## Priority screen fixes (from the critique)
1. System: hero gauges (240° arc, mono number) for the worst metrics; full-width sparklines with gradient fill and
   current-value dot; storage sorted worst-first, healthy ones collapsed; 8dp critical bars; one severity model.
2. Dashboard: hero status strip (worst metric + sentence + heartbeat) → 2-column bento (Homelab, Agents, Continue) →
   Activity (4 rows + See all). Drop Quick Actions (drawer has them), hide "Needs you" when empty.
3. Chat: one-line header (title 14sp 500 + model in mono 11sp); "Full access" moves to a shield chip by the composer
   or the menu; Hermes presence mark (orbit glyph beside assistant messages, rotates while streaming); assistant
   messages flat on the background, bubble only for the user; reply actions small and quiet.
4. Copy: human first, raw value second ("Idle" not "Busy: false"; agent failure "morning-briefing couldn't run" +
   "Missing Python module 'google'" + "Fix with Hermes"); PID etc. in a collapsed Details row.
5. Hygiene: no text < 12sp, touch targets ≥ 48dp, gate TypingDots/VoiceSheet orb/Essentials pulse on
   Ui.motionEnabled(), no dark-only colour literals outside tokens.
