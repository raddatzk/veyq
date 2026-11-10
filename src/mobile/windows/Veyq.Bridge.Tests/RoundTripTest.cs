using System;
using System.IO;
using System.Text.Json.Nodes;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

/// <summary>
/// The C# half of the bridge's round trip (ADR 0123's Consequences, the bridge), as the Rust test is: every sample
/// the JVM wrote (`WireTest`, one per variant of every type the roots reach) reads as its generated type and writes back
/// to the same JSON, field for field. A sample of a type this side lacks is red (<see cref="WireTypes.Reserialize"/> is
/// generated with the types), and so is an empty directory.
/// </summary>
[TestClass]
public sealed class RoundTripTest
{
    [TestMethod]
    public void EverySampleTheCoreWroteReadsBackFieldForField()
    {
        var dir = Environment.GetEnvironmentVariable("VEYQ_SAMPLES")
            ?? Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "build", "generated", "bridge", "samples");
        Assert.IsTrue(Directory.Exists(dir), $"no samples at {dir}: `./gradlew :bridge:jvmTest --tests '*WireTest*'` writes them");
        var read = 0;
        foreach (var path in Directory.GetFiles(dir, "*.json"))
        {
            var name = Path.GetFileNameWithoutExtension(path);
            var dash = name.LastIndexOf('-');
            Assert.IsTrue(dash > 0, $"{name}: not <Type>-<n>");
            var type = name[..dash];
            var json = File.ReadAllText(path);
            string? back;
            try
            {
                back = WireTypes.Reserialize(type, json);
            }
            catch (System.Text.Json.JsonException e)
            {
                throw new AssertFailedException($"{name}: {e.Message}", e);
            }
            Assert.IsNotNull(back, $"{name}: no wire type {type} on this side");
            Assert.IsTrue(JsonNode.DeepEquals(JsonNode.Parse(json), JsonNode.Parse(back)), $"{name}:\n  written {json}\n  read back {back}");
            read++;
        }
        Assert.IsTrue(read > 0, $"no sample in {dir}");
        Console.WriteLine($"{read} samples read back");
    }
}
