package game.server;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.sql.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class SchemaTest {
    @Test void springKeepsEntirePostgresFunctionBodies() throws Exception {
        Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        ScriptUtils.executeSqlScript(connection,new EncodedResource(new ClassPathResource("schema.sql")),false,false,"--","@@","/*","*/");
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);verify(statement,times(11)).execute(sql.capture());
        var functions=sql.getAllValues().stream().filter(x->x.startsWith("CREATE OR REPLACE FUNCTION")).toList();
        assertEquals(3,functions.size());assertTrue(functions.stream().allMatch(x->x.contains("END; $$")));
    }
}
