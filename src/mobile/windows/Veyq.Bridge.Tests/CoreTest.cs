using System;
using System.Collections.Concurrent;
using System.Threading.Tasks;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

/// <summary>
/// The wrapper over the real veyq.dll (Windows only: the DLL is a mingw x64 one), as `bridge/smoke/smoke.c` runs the
/// library from C: the core starts once, a watch gets the way in's gate, a call is answered, the host's store is asked,
/// a call before a tenant is open is refused, an ended watch gets no more updates, and the core shuts down. It runs once
/// per process — veyq_init starts the core once.
/// </summary>
[TestClass]
public sealed class CoreTest
{
    private static readonly TimeSpan Wait = TimeSpan.FromSeconds(30);

    [TestMethod]
    [OSCondition(OperatingSystems.Windows)]
    public async Task TheCoreAnswersThroughTheWrapper()
    {
        var store = DeviceStore.InMemory();
        Assert.AreEqual(Start.Started, Core.Start(new Config { device = "Veyq.Bridge.Tests" }, store));
        Assert.AreEqual(Start.AlreadyStarted, Core.Start(new Config { device = "Veyq.Bridge.Tests" }, DeviceStore.InMemory()));
        try
        {
            var gates = new BlockingCollection<Update>();
            var watch = Core.Watch(new Watched.Session(), gates.Add, e => Assert.Fail(e.ToString()));
            Assert.IsNotNull(watch, "the Session watch was refused");
            Assert.IsInstanceOfType<Gate.Starting>(GateOf(Next(gates)));

            var started = await Core.CallAsync(new Call.SessionStart()).WaitAsync(Wait);
            Assert.AreEqual(new Answer.Done(), started.Answer);
            Assert.IsNull(started.Payload, "a payload beside an answer that is no Bytes");
            while (GateOf(Next(gates)) is not Gate.SignIn) { }
            Assert.IsGreaterThan(0, store.Answered, "the core asked the host's store");

            var (refused, _) = await Core.CallAsync(new Call.SearchQuery { query = "x" }).WaitAsync(Wait);
            Assert.AreEqual(new Answer.Refused { why = Why.NoTenantOpen }, refused);

            watch.Dispose();
            var before = gates.Count;
            // a sign-in nobody answers moves the gate, which no ended watch may report
            await Core.CallAsync(new Call.SessionSignIn { server = "http://127.0.0.1:9", email = "a@b.c", password = "x", secretKey = "x" }).WaitAsync(Wait);
            await Task.Delay(300);
            Assert.AreEqual(before, gates.Count, "an ended watch got an update");
        }
        finally
        {
            Core.Shutdown();
        }
    }

    private static Update Next(BlockingCollection<Update> updates) =>
        updates.TryTake(out var u, Wait) ? u : throw new AssertFailedException("no update arrived");

    private static Gate GateOf(Update u) => ((Update.Session)u).gate;
}
