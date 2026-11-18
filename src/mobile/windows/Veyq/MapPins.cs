// A map pin's name for a screen reader (UI Automation): a pin standing for one Memory is that Memory's, a cluster is the
// cluster — "3 Memories here", the title of the list its click opens (map_cluster_title) —, never the one Memory whose
// cover it shows (#946). The drawing is RasterMap.Pin's.
namespace Veyq;

internal static class MapPins
{
    public static string Name(int count, string memory) => count > 1 ? Words.MapClusterTitle(count) : memory;
}
