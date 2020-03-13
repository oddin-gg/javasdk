package com.oddin.oddsfeedsdk.utils

import java.util.*
import javax.xml.datatype.XMLGregorianCalendar

class Utils {
    companion object {

        fun parseDate(calendar: XMLGregorianCalendar?): Date? {
            if (calendar?.timezone == -2147483648) {
                calendar.timezone = 0
            }

            return calendar?.toGregorianCalendar()?.time
        }

    }
}