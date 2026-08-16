/**
 * Main.js — the JavaScript twin of Main.java, and the file M10 is traced in.
 *
 * It is not a program anybody should run. Every section exists because some part of the JavaScript
 * stack is supposed to do something visible to it, and each one says what that is — so a section that
 * looks like the section above it, and should not, is the bug.
 *
 * Sections are added as milestones land. What is here now:
 *
 *   M10.1  colours, from the tree-sitter grammar, and the EDITING affordances that arrived with
 *          Language.JAVASCRIPT: toggle-comment, auto-closing brackets, the backtick pair, and the
 *          `.` that opens a completion list.
 *   M10.2  Rhino behind the file. The status bar names it; a syntax error is a real squiggle from a
 *          real parser, on the offset the parser reported; Run recognises the file and refuses one
 *          that does not compile.
 *
 * Still to come, in the order they arrive:
 *   M10.3  the band-refusal diagnostics, re-titled — see the bottom of this file
 *   M10.4  parameters, locals, consts and captures each coloured as what they are
 *   M10.5  Shift+F10 actually runs it
 *   M10.6  hover and type inference   M10.7  completion   M10.8  Quick Documentation
 */

'use strict';

// ── Constants and the literal forms ─────────────────────────────────────────────────────────────
// M10.4 will draw these as CONSTANTS, distinct from the locals below — a distinction no grammar can
// make, because nothing in the shape of a name says whether it was declared with `const`.

const MAX_RETRIES = 5;
const TIMEOUT_MS = 2500;
const GOLDEN = 1.618033988749;
const HEX = 0xdeadbeef;
const NOTHING = null;
const MISSING = undefined;
const PATTERN = /^cg-([a-z]+)-(\d+)$/;

// The backtick pair is the one piece of punctuation JavaScript has and Java does not. Type one and
// the editor writes its partner; the lexer knows it opens a string, which is what stops the quote
// inside it from painting the rest of the line.
const GREETING = `hello, ${'world'} — he said "hi" and it stayed inside the template`;

// ── Functions, parameters and captures ──────────────────────────────────────────────────────────
// M10.4 colours all four of these differently: `total` is a local, `items` and `rate` are parameters,
// `seen` is a local CAPTURED by the closure below it, and `applyRate` is a function.

function summarise(items, rate) {
    let total = 0;
    const seen = [];
    for (var i = 0; i < items.length; i++) {
        total += items[i] * rate;
        seen.push(items[i]);
    }
    const report = function () {
        // `seen` is captured here, and `total` is not — that difference is the whole point of the
        // colour, and it is invisible to anything that has not resolved the scopes.
        return seen.length;
    };
    return { total: total, count: report() };
}

const applyRate = (value, rate) => value * rate;

// ── Objects, and the dot that opens a list ──────────────────────────────────────────────────────
// Type a `.` after `settings` and a completion list opens. At M10.2 it opens EMPTY, which is the
// honest state: the trigger is wired and the provider is not. At M10.7 it lists these three.

const settings = {
    retries: MAX_RETRIES,
    timeout: TIMEOUT_MS,
    label: 'scratch',
};

function describe(config) {
    return config.label + ' (' + config.retries + ' retries)';
}

// ── Java interop — the half of this language that matters here ──────────────────────────────────
// Rhino reaches Java by reflection at call time. M10.6 resolves these THROUGH THE JAVA ENGINE, so the
// members offered and the signatures shown are the same ones a .java file would get, quoted from the
// same src.zip. Until then they are ordinary names.

function useJava() {
    var list = new java.util.ArrayList();
    list.add('one');
    list.add('two');
    var joined = java.lang.String.join(', ', list);
    return joined;
}

// ── Output ──────────────────────────────────────────────────────────────────────────────────────
// M10.5 wires `console.log` and `print` to the Run panel, attributed to the line that printed. Until
// then Run refuses the file, because a Run button that silently did nothing is worse than one that
// says it is not built.

function main() {
    var result = summarise([1, 2, 3], 1.5);
    console.log('total: ' + result.total + ' over ' + result.count + ' items');
    console.log(describe(settings));
    console.log(GREETING);
    return result;
}

main();

// ── Uncomment to see the engine disagree with the grammar ───────────────────────────────────────
//
// The grammar parses modern JavaScript perfectly and colours all of this. The ENGINE is what refuses
// it, and refuses different things per band — measured, not guessed: `class`, `import`/`export` and
// `async`/`await` are refused by every band we ship, while `?.` and `??` work on Java 11+ and not on
// Java 8. That gap is the whole reason parse diagnostics come from Rhino rather than from tree-sitter.
//
// At M10.2 the message is Rhino's own, which for `class` reads like a missing semicolon. M10.3 is
// where it becomes "classes are not supported by this engine", and where a file written on Java 17
// gets a warning about the Java 8 host it will not load on.
//
// class Point {
//     constructor(x, y) { this.x = x; this.y = y; }
// }
//
// import { thing } from './lib.js';
//
// async function fetchThing() { return await thing(); }
//
// const maybe = settings?.label ?? 'none';
