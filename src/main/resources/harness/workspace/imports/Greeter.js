// Middle of the chain — a module that itself imports a module.
//
// This is the file that decided the implementation. Rhino ships CommonJS and it would have been the
// natural thing to bind our `import` statement to, except that `Require` builds each module's scope
// internally with no hook to inject a binding into — so a module with an import of its own could not be
// served at all. Which is exactly this file, and it is the ordinary shape rather than an edge case.
//
// Things worth trying:
//   * Type `Formatter.` below — the popup should offer `shout` and `quiet`, read out of the other file.
//   * Ctrl+B on `Formatter` opens it.
//   * Delete the import line and `Formatter` should go unresolved — then undo.
//   * `module.exports = { ... }` is read too; try replacing the two `exports.x =` lines with one object
//     and check App.js still completes.

import util.Formatter;

exports.greet = function (who) {
    return Formatter.shout('hello, ' + who);
};

exports.murmur = function (who) {
    return Formatter.quiet('hello, ' + who);
};

// A plain value export, so the popup has something that is not a function in it.
exports.defaultName = 'world';
