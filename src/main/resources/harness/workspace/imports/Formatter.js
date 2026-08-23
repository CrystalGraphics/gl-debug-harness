// Bottom of the chain — imports nothing, exports two functions.
//
// Part of the M15 S6/S7 JavaScript fixture. Three files, mirroring the Java one next door so the two can
// be compared directly:
//
//   * App.js       imports util.Greeter and runs it
//   * util/Greeter.js  imports util.Formatter — a module that itself imports a module
//   * util/Formatter.js  this one, the leaf
//
// It sits under `src/main/js`, which is what makes any of it work: a source root is what turns a path
// into a name, so this file is `util.Formatter` and that is what an `import` names. A file under no root
// has no derived name and cannot be imported at all.
//
// Things worth trying here:
//   * Type `exports.` on a new line — nothing clever should happen; `exports` is an ordinary object.
//   * Rename `shout` and watch Greeter.js and App.js stop offering it, with NO SAVE.
//   * Add `exports.whisper = function (t) { return t.toLowerCase(); };` and it should appear behind
//     `Formatter.` in Greeter.js on the next analysis.

exports.shout = function (text) {
    if (!text) {
        return '';
    }
    return String(text).trim().toUpperCase() + '!';
};

exports.quiet = function (text) {
    return text ? String(text).trim().toLowerCase() : '';
};
