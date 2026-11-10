// The words' catalog as MRT Core holds it: the .resw files generate_words.py writes, compiled into the app's .pri by
// the build (Strings/<culture>/Resources.resw, the culture its language qualifier).
using System;
using Microsoft.Windows.ApplicationModel.Resources;

namespace Veyq;

public static class MrtWords
{
    /// <summary>
    /// The lookup over <paramref name="priFile"/> (null: the app's own, beside its exe) in <paramref name="language"/>
    /// (a BCP-47 tag; null: the person's languages, as Windows orders them).
    /// </summary>
    public static Func<string, string?> Open(string? priFile, string? language)
    {
        var manager = priFile is null ? new ResourceManager() : new ResourceManager(priFile);
        var context = manager.CreateResourceContext();
        if (language is not null) context.QualifierValues["Language"] = language;
        var map = manager.MainResourceMap.GetSubtree("Resources");
        return name => map.TryGetValue(name, context)?.ValueAsString;
    }
}
