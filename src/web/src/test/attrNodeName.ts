// happy-dom (20.10) answers an attribute's `nodeName` with '' where the DOM standard - and every browser - answers its
// qualified name. ExifReader reads XMP's attribute-form properties (`<rdf:Description xmp:CreateDate="…"/>`, the form
// Lightroom writes, inbox #808) by `nodeName`, so over happy-dom it reads none of them. A suite that parses those installs
// this first; it changes nothing a browser does.
export function qualifiedAttrNodeNames(): void {
  Object.defineProperty(Attr.prototype, 'nodeName', { configurable: true, get(this: Attr) { return this.name } })
}
