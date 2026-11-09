package me.raddatz.veyq.mobile.media

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun localWallClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? {
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    calendar.timeZone = NSTimeZone.localTimeZone
    val parts = NSDateComponents().apply {
        this.year = year.toLong()
        this.month = month.toLong()
        this.day = day.toLong()
        this.hour = hour.toLong()
        this.minute = minute.toLong()
        this.second = second.toLong()
    }
    val date = calendar.dateFromComponents(parts) ?: return null
    return (date.timeIntervalSince1970 * 1000).toLong()
}
