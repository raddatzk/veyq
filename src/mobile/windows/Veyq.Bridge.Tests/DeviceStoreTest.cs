using System;
using System.IO;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

[TestClass]
public sealed class DeviceStoreTest
{
    [TestMethod]
    public void TheStoreKeepsWhatTheCoreWroteAcrossARestart()
    {
        var dir = Path.Combine(Path.GetTempPath(), $"veyq-store-{Environment.ProcessId}-{Guid.NewGuid():N}");
        var file = Path.Combine(dir, "device-store.json");
        try
        {
            var store = DeviceStore.Open(file);
            HostReply Read(DeviceStore s, string name) => s.Answer(new HostRequest.StoreRead { name = name });
            Assert.AreEqual(new HostReply.Value { value = null }, Read(store, "a"));
            Assert.AreEqual(new HostReply.Done(), store.Answer(new HostRequest.StoreWrite { name = "a", value = "1" }));
            Assert.AreEqual(new HostReply.Done(), store.Answer(new HostRequest.StoreWrite { name = "b", value = "2" }));
            Assert.AreEqual(new HostReply.Done(), store.Answer(new HostRequest.StoreRemove { name = "b" }));
            var again = DeviceStore.Open(file);
            Assert.AreEqual(new HostReply.Value { value = "1" }, Read(again, "a"));
            Assert.AreEqual(new HostReply.Value { value = null }, Read(again, "b"));
        }
        finally
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
        }
    }
}
