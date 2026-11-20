// What an import's dialog shows at each step, without a widget (ADR 0152 decision 5; the web's ImportSubtreeDialog.vue
// line for line): ImportDialog draws it, ImportTextTest holds it. Every value is the
// core's (ImportShown, core's export/ImportDialog); this only decides where each lands — the web's summary paragraph, its
// list of what is left out, its no-room warning, its error message and its buttons.
using System;
using System.Collections.Generic;
using System.Linq;
using Veyq.Bridge.Wire;

namespace Veyq;

internal static class ImportText
{
    /// <summary>The dry run said nothing is importable (shown in the summary's place, as the web does).</summary>
    private const string Nothing = "import_nothing";

    /// <summary>The tenant has no room (the web's warning, not its error).</summary>
    private const string NoRoom = "import_no_room";

    /// <summary>
    /// The dialog's face: whether it still reads (<see cref="Reading"/>); the download's top name (<see cref="Root"/>), the
    /// summary line or the nothing-to-import line; what is left out (<see cref="LeftOut"/>, under the web's heading); the
    /// no-room warning; whether the ownership notice shows; a run's line and its fraction; the error (<see cref="Problem"/>:
    /// a refusal, a failure); and the buttons — the primary's word and whether it is shown and enabled, *Upload as a file*'s.
    /// </summary>
    public sealed record Face(
        bool Reading,
        string? Root,
        string? Summary,
        string? Nothing,
        IReadOnlyList<string> LeftOut,
        string? NoRoom,
        bool Notice,
        string? Progress,
        double Fraction,
        string? Problem,
        string? Primary,
        bool PrimaryEnabled,
        bool AsFile,
        bool AsFileEnabled);

    public static Face Of(ImportShown s)
    {
        var reading = s.step == ImportStep.Reading;
        var invalid = s.step == ImportStep.Invalid;
        var running = s.step == ImportStep.Running;
        var planned = !reading && !invalid;
        // the web's "{text} ({detail})" and "{quota} {failed}": one message, its parts joined by a space
        var problem = string.Join(" ", s.said.Where(n => n.msg != Nothing && n.msg != NoRoom).Select(Said.Notice));
        return new Face(
            Reading: reading,
            Root: planned && s.root.Length > 0 ? s.root : null,
            Summary: planned && s.summary is { } summary ? Said.Notice(summary) : null,
            Nothing: s.said.FirstOrDefault(n => n.msg == Nothing) is { } nothing ? Said.Notice(nothing) : null,
            LeftOut: planned ? s.leftOut.Select(Said.Notice).ToList() : [],
            NoRoom: s.said.FirstOrDefault(n => n.msg == NoRoom) is { } noRoom ? Said.Notice(noRoom) : null,
            Notice: planned && s.summary is not null && s.step != ImportStep.Done,
            Progress: running && s.progress is { } p ? Said.Notice(p) : null,
            Fraction: running ? (double)s.done / Math.Max(1, s.total) : 0,
            Problem: problem.Length > 0 && s.step != ImportStep.Done ? problem : null,
            // the web: shown while it reads and wherever something is importable, never for a refused download
            Primary: !invalid && (reading || s.summary is not null) ? Said.Key(s.importWord) : null,
            PrimaryEnabled: s.canImport,
            AsFile: s.canAsFile,
            AsFileEnabled: s.canAsFile && !running);
    }

    /// <summary>The toast a done import leaves (the web's toast.success): the core's imported line.</summary>
    public static string Done(ImportShown s) => string.Join(" ", s.said.Select(Said.Notice));
}
