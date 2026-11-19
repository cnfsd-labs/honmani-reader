package app.honmani.reader.ui.opds

import app.honmani.reader.R
import app.honmani.reader.data.opds.OpdsException
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogErrorTest {
    @Test
    fun errorsAChangedAddressOrLoginCanFix_offerTheServerSettings() {
        val offered = OpdsException.Reason.entries.associateWith(::catalogErrorEditAction).filterValues { it != null }
        assertEquals(
            mapOf(
                OpdsException.Reason.UNAUTHORIZED to R.string.catalog_change_login,
                OpdsException.Reason.NOT_A_CATALOGUE to R.string.catalog_edit_server,
                OpdsException.Reason.CERTIFICATE to R.string.catalog_edit_server,
                OpdsException.Reason.HTTP_ONLY to R.string.catalog_edit_server,
            ),
            offered,
        )
    }
}
