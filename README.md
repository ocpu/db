# db
A super charged database connection with a model/active record.

To create a connection you can use the `io.opencubes.sql.Databse` to either
create a database connection or reuse a exiting one. With this new database
instance you can execute any SQL query with `db.execute`.

[Read more][db-execute]

```kotlin
val db = Database("sqlite:/path/to/db.sqlite")
db.execute("SELECT * FROM users")
```

## Model/Active record

This project includes a database model. More information [here][model].

```kotlin
class User : Model(db, "users") {
  @Auto
  val id by value<Int>()
  var name by value<String>()
}
```

[db-execute]: https://github.com/ocpu/db/blob/kotlin-master/src/main/kotlin/io/opencubes/sql/Database.kt#L45-L54
[model]: https://github.com/ocpu/db/blob/kotlin-master/src/main/kotlin/io/opencubes/sql/Model.kt#L24-L43
