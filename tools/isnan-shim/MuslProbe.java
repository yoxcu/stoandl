// Driver-level check of the musl __isnan fix (SqliteNative), without the daemon and without touching
// libpebble3.db: opens an in-memory database through the same androidx BundledSQLiteDriver libpebble3
// uses and runs floating-point SQL (AVG → avgFinalize, a REAL literal → sqlite3AtoF, round()).
// TESTING.md §5.32a runs it.
//
// A JRE has no javac, so compile it where the fat JAR was built:
//   javac --release 21 -cp build/libs/stoandl-*-all.jar -d probe tools/isnan-shim/MuslProbe.java
// copy probe/MuslProbe.class to the target, and run it against the installed JAR:
//   XDG_CACHE_HOME=<scratch> java --enable-native-access=ALL-UNNAMED -Djava.io.tmpdir=<scratch> \
//     -cp <stoandl.jar>:<dir with MuslProbe.class> MuslProbe
// Success prints "MUSLPROBE OK avg=2.0 mul=3.0 round=2.6". --no-prepare skips SqliteNative: on musl
// that run must die with SIGSEGV (the negative control that proves the crash is real).
import androidx.sqlite.driver.bundled.BundledSQLiteDriver;

public class MuslProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !args[0].equals("--no-prepare")) de.yoxcu.stoandl.SqliteNative.INSTANCE.prepare();
        var connection = new BundledSQLiteDriver().open(":memory:");
        var statement = connection.prepare(
            "SELECT AVG(x), 1.5 * 2, round(2.567, 1) FROM (SELECT 1.5 AS x UNION ALL SELECT 2.5)");
        statement.step();
        System.out.println("MUSLPROBE OK avg=" + statement.getDouble(0) + " mul=" + statement.getDouble(1)
            + " round=" + statement.getDouble(2));
    }
}
