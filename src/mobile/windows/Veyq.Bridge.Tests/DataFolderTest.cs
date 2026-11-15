using System;
using System.IO;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Veyq.Bridge.Wire;

namespace Veyq.Bridge.Tests;

[TestClass]
public sealed class DataFolderTest
{
    // The packaged app keeps its data in its package's folder even where an unpackaged build left %LOCALAPPDATA%\veyq
    // with a trusted device in it: it starts as a clean install, and that state stays the development build's alone.
    [TestMethod]
    public void ThePackagedAppNeverOpensTheUnpackagedBuildsData()
    {
        var localAppData = Path.Combine(Path.GetTempPath(), $"veyq-data-{Environment.ProcessId}-{Guid.NewGuid():N}");
        var packageFolder = Path.Combine(localAppData, "Packages", "app.veyq.Veyq_b17envnammnz6", "LocalState");
        try
        {
            var dev = DeviceStore.OpenIn(DataFolder.Unpackaged(localAppData));
            Assert.AreEqual(new HostReply.Done(), dev.Answer(new HostRequest.StoreWrite { name = "trusted", value = "dev" }));
            Assert.IsTrue(File.Exists(Path.Combine(localAppData, "veyq", "device-store.json")));

            var packaged = DataFolder.Resolve(packageFolder, localAppData);
            Assert.AreEqual(packageFolder, packaged);
            Assert.AreNotEqual(Path.GetFullPath(DataFolder.Unpackaged(localAppData)), Path.GetFullPath(packaged));
            Assert.AreEqual(new HostReply.Value { value = null },
                DeviceStore.OpenIn(packaged).Answer(new HostRequest.StoreRead { name = "trusted" }));

            Assert.AreEqual(Path.Combine(localAppData, "veyq"), DataFolder.Resolve(null, localAppData));
            // the offline stores live in whichever data folder this process has, the package's under the MSIX
            Assert.AreEqual(Path.Combine(packageFolder, "offline"), DataFolder.Offline(packaged));
            Assert.AreEqual(Path.Combine(localAppData, "veyq", "offline"), DataFolder.Offline(DataFolder.Resolve(null, localAppData)));
            Assert.AreEqual(new HostReply.Value { value = "dev" },
                DeviceStore.OpenIn(DataFolder.Resolve(null, localAppData)).Answer(new HostRequest.StoreRead { name = "trusted" }));
        }
        finally
        {
            if (Directory.Exists(localAppData)) Directory.Delete(localAppData, recursive: true);
        }
    }
}
