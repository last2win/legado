package android.database
interface Cursor
open class MatrixCursor(columns: Array<String>, count: Int) : Cursor { fun addRow(row: Array<String>) {} }
