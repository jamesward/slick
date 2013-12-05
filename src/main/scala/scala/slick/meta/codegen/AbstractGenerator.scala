package scala.slick.meta.codegen

import scala.slick.{meta => m}
import scala.slick.lifted.ForeignKeyAction
import scala.slick.ast.ColumnOption

/**
 * Slick code generator providing the base structure and facilities.
 * It contains a subclass as a generator for Tables, which again contains
 * subclasses for Column, etc.
 * The implementation follows the virtual class pattern, which allows flexible
 * customization by overriding the inner classes (following the pattern).
 * @see http://lampwww.epfl.ch/~odersky/papers/ScalableComponent.html
*/
abstract class AbstractGenerator[Code](model: m.Model)
                   extends GeneratorHelpers[Code]{
  model.assertConsistency

  // virtual class pattern
  /** Table code generator "virtual" class. */
  type Table <: TableDef
  /** Creates a Table code generator. Override for customization. */
  def Table: m.Table => Table

  /** Table code generators. */
  final lazy val tables: Seq[Table] = model.tables.map(Table)
  /** Table code generators indexed by db table name. */
  final lazy val tablesByName: Map[m.QualifiedName,Table] = tables.map(t => t.meta.name -> t).toMap

  // pulled out here to make this common use case simpler
  /** Maps database table name to Table class and value name */
  def tableName = (dbName: String) => dbName.toCamelCase
  /** Maps database table name to entity case class name */
  def entityName = (dbName: String) => dbName.toCamelCase+"Row"

  // -----------------------------------------------------
  // Code generators for the different meta model entities
  /**
   * Code generator for table related code
   * @param meta Jdbc meta data
  */
  abstract case class TableDef(val meta: m.Table){
    _table =>
    // virtual class pattern
    /** Column code generator */
    type Column     <: ColumnDef
    /** Primary key code generator */
    type PrimaryKey <: PrimaryKeyDef
    /** Foreign key code generator */
    type ForeignKey <: ForeignKeyDef
    /** Index code generator */
    type Index      <: IndexDef
    /** Creates a column      code generator. Override for customization. */
    def Column    : m.Column     => Column
    /** Creates a primary key code generator. Override for customization. */
    def PrimaryKey: m.PrimaryKey => PrimaryKey
    /** Creates a foreign key code generator. Override for customization. */
    def ForeignKey: m.ForeignKey => ForeignKey
    /** Creates an index      code generator. Override for customization. */
    def Index     : m.Index      => Index

    // component generators
    /** Column code generators. */
    final lazy val columns: Seq[Column] = meta.columns.map(Column)
    /** Column code generators indexed by db column name */
    final lazy val columnsByName: Map[String,Column] = columns.map(c => c.meta.name -> c).toMap
    /** Primary key code generator, if this table has one */
    final lazy val primaryKey: Option[PrimaryKey] = meta.primaryKey.map(PrimaryKey)
    /** Foreign key code generators */
    final lazy val foreignKeys: Seq[ForeignKey] = meta.foreignKeys.map(ForeignKey)
    /** Index code generators */
    final lazy val indices: Seq[Index] = meta.indices.map(Index)

    /** Generates the complete code for this table and its subordinate generators. */
    def code: Seq[Code] = {
      (
        if(entityClassEnabled)
          Seq(docWithCode(entityClassDoc,entityClassCode))
        else
          Seq()
      ) ++
      (
        if(plainSQLEnabled)
          Seq(docWithCode(plainSQLDoc,plainSQLCode))
        else
          Seq()
      ) ++ Seq(
        docWithCode(tableClassDoc,tableClassCode),
        docWithCode(tableValueDoc,tableValueCode)
      )
    }

    // * projection and types
    /** The * projection that accumulates all columns and map them if mappingEnabled is true*/
    def star: Code
    /** Indicates weather a ? projection should be generated. */
    def optionEnabled: Boolean = mappingEnabled && columns.exists(c => !c.meta.nullable)
    /** The ? projection to produce an Option row. Useful for outer joins. */
    def option: Code
    /** Type of the * projection in case it mappingEnabled is false. */
    def types: Code = compound(columns.map(_.tpe))
    /** The type of the elements this table yields. */
    final def tpe: Code = if(mappingEnabled) mappedType else types

    // mapping
    /** Indicates if this table should be mapped using <> to a factory and extractor or not. (Consider overriding entityClassEnabled instead, which affects this, too.) */
    def mappingEnabled = entityClassEnabled
    /** The type to which elements of this table are mapped (in case mappingEnabled is true). */
    def mappedType: Code
    /** Function that constructs an entity object from the unmapped values */
    def factory: Code
    /** Function that constructs an Option of an entity object from the unmapped Option values */
    def optionFactory: Code
    /** Function that extracts the unmapped values from an entity object */
    def extractor: Code

    // entity class (mapped case class)
    /** Indicates if an entity case class should be generated for this table. (This also set mappingEnabled to false unless you override it.) */
    def entityClassEnabled = columns.size <= 22
    /** Scala doc for entity case class */
    def entityClassDoc: Option[String] = Some(s"Entity class storing rows of table $tableValueName")
    /** Name used for entity case class */
    def entityClassName: String = entityName(meta.name.table)
    /** Generates the entity case class (holding a complete row of data of this table).*/
    def entityClassCode: Code
    /** Traits the Table class should inherit */
    def entityClassParents: Seq[Code] = Seq()

    // GetResult mapper to use with plain SQL
    /** Indicates if an implicit GetResult mapper should be generated for this table. */
    def plainSQLEnabled: Boolean = true
    /** Scala doc for GetResult mapper */
    def plainSQLDoc: Option[String] = Some(s"GetResult implicit for fetching $entityClassName objects using plain SQL queries")
    /** Name used for GetResult mapper */
    def plainSQLName: String = "Get"+tableName(meta.name.table)
    /** Generates the GetResult mapper definition code.*/
    def plainSQLCode: Code

    // Table class
    /** Scala doc for the Table class */
    def tableClassDoc: Option[String] = Some(s"Table description of table ${meta.name.table}. Objects of this class serves as prototypes for rows in queries.")
    /** Name for the Table class */
    def tableClassName: String = tableName(meta.name.table)
    /** Generates the Table class code. */
    def tableClassCode: Code
    /** Generates the body of the Table class as individual statements grouped into logical groups. */
    def tableClassBody: Seq[Seq[Code]] = Seq(
      Seq(star) ++ (if(optionEnabled) Seq(option) else Seq()),
      columns    .map(x => docWithCode(x.doc,x.code)),
      // H2 apparently needs primary key and autoinc to be specified together, so we place single primary keys as column options
      primaryKey.map(x => docWithCode(x.doc,x.code)).toSeq,
      foreignKeys.map(x => docWithCode(x.doc,x.code)),
      indices    .map(x => docWithCode(x.doc,x.code))
    )
    /** Traits the Table class should inherit */
    def tableClassParents: Seq[Code] = Seq()

    // Table value (TableQuery)
    /** Scala doc for the Table/TableQuery value */
    def tableValueDoc: Option[String] = Some(s"Collection-like TableQuery object for table $tableValueName")
    /** Name used for the Table/TableQuery value */
    def tableValueName: String = tableName(meta.name.table)
    /** Generates the definition of the Table/TableQuery value (a collection-like value representing this database table). */
    def tableValueCode: Code

    // generator classes
    /**
     * Code generator for column related code.
     * @param meta Jdbc meta data
     */
    abstract case class ColumnDef(val meta: m.Column){
      /** Table code generator */
      final lazy val table = _table
      /**
       * Underlying Scala type of this column.
       * Override this to just affect the data type but preserve potential Option-wrapping.
       * Override tpe for taking control of Option.wrapping.
       * Override GeneratorHelpers#sqlTypeToClass for generic adjustments.
       */
      def rawType: Code = mapJdbcType(meta.jdbcType)
      /** Possibly Option-wrapped Scala type of this column. @see rawType */
      final def tpe: Code = if(meta.nullable) toOption(rawType) else rawType

      /** Generates code for the ColumnOptions (DBType, AutoInc, etc.) */
      def options: Iterable[Code]

      /** Name for the column definition used in Scala code */
      def name: String = meta.name.toCamelCase.uncapitalize
      /** Scala doc comment for the column definition */
      def doc: Option[String] = Some({
        s"""Database column ${meta.name} ${meta.options.map(_.toString).mkString(", ")}"""
      })
      /** Scala code defining the column */
      def code: Code
    }

    /**
     * Code generator for primary key related code.
     * (Currently only used for composite primary keys.)
     * @param meta Jdbc meta data
     */
    abstract case class PrimaryKeyDef(val meta: m.PrimaryKey){
      /** Table code generator */
      final lazy val table = _table
      /** Columns code generators in correct order */
      final lazy val columns: Seq[Column] = meta.columns.map(_.name).map(columnsByName)
      /** Name used in the db or a default */
      lazy val dbName = meta.name.getOrElse("PRIMARY_KEY_"+freshInteger)
      /** Name for the primary key definition used in Scala code */
      def name = dbName.toCamelCase.uncapitalize
      /** Scala doc comment for the definition */
      def doc: Option[String] = Some(s"Primary key of ${table.tableValueName}")
      /** Scala code defining this primary key */
      def code: Code
    }

    /**
     * Code generator for foreign key related code.
     * @param meta Jdbc meta data
     */
    abstract case class ForeignKeyDef(val meta: m.ForeignKey){
      /** Referencing Table code generator */
      final lazy val referencingTable = _table
      /** Referencing columns code generators */
      final lazy val referencingColumns: Seq[Column] = meta.referencingColumns.map(_.name).map(columnsByName)
      /** Referenced Table code generator */
      final lazy val referencedTable: Table = tablesByName(meta.referencedTable)
      /** Referenced Columns code generators */
      final lazy val referencedColumns: Seq[TableDef#Column] = meta.referencedColumns.map(_.name).map(referencedTable.columnsByName)
      /** Generates the ForeignKeyAction code for the ON UPDATE behavior rule. */
      def onUpdate: Code
      /** Generates the ForeignKeyAction code for the ON Delete behavior rule. */
      def onDelete: Code
      /** Name used in the db or a default */
      lazy val dbName = meta.name.getOrElse("PRIMARY_KEY_"+freshInteger)
      /** Name for the foreign key definition used in Scala code. (Default: if no name conflict, name of referenced table, else database name.) */
      def name: String = {
        val preferredName = referencedTable.tableValueName.uncapitalize
        if(
          // multiple foreign keys to the same table
          foreignKeys.exists(_.referencedTable == referencedTable)
          // column name conflicts with referenced table name
          || columns.exists(_.name == preferredName)
        )
          dbName.toCamelCase.uncapitalize
        else
          preferredName
      }
      /** Scala doc comment for the definition */
      def doc: Option[String] = Some(s"Foreign key ${meta.name} referencing ${referencedTable.tableValueName}")
      /** Scala code defining this foreign key */
      def code: Code
    }

    /**
     * Code generator for index related code
     * @param meta Jdbc meta data
     */
    abstract case class IndexDef(val meta: m.Index){
      /** Table code generator */
      final lazy val table = _table
      /** Columns code generators */
      final lazy val columns: Seq[Column] = meta.columns.map(_.name).map(columnsByName)
      /** Name used in the db or a default */
      lazy val dbName = meta.name.getOrElse("INDEX_"+freshInteger)
      /** The name used in Scala code */
      def name = meta.name.map("INDEX_"+_).getOrElse(dbName).toCamelCase.uncapitalize
      /** Name for the index definition used in Scala code */
      def doc: Option[String] = Some(
        (if(meta.unique)"Uniqueness " else "")+
        s"""Index over ${columns.map(_.name).mkString("(",",",")")}"""
      )
      /** Scala code defining this index */
      def code: Code
    }
  }
}

/** Helper methods for code generation */
trait GeneratorHelpers[Code]{
  private var _freshInteger = 0
  def freshInteger = {
    _freshInteger+=1
    _freshInteger
  }
  /** Assemble doc comment with scala code */
  def docWithCode(comment: Option[String], code:Code): Code

  /** Indents all but the first line of the given string */
  def indent(code: String): String = code.split("\n").mkString("\n"+"  ")

  /** Wrap the given type into an Option type */
  def toOption(tpe: Code): Code

  /**
   * Creates a compound type or value from a given sequence of types or values.
   * Defaults to a tuple. Can be used to switch to HLists.
   */
  def compound(valuesOrTypes: Seq[Code]): Code

  /** Generates code for the Scala type corresponding to the given java.sql.Types type. (Uses mapJdbcTypeString.) */
  def mapJdbcType(jdbcType: Int): Code

  /** Converts from java.sql.Types to the corresponding Java class name (with fully qualified path). */
  def mapJdbcTypeString(jdbcType: Int): String = {
    import java.sql.Types._
    // see TABLE B-1 of JSR-000221 JBDCTM API Specification 4.1 Maintenance Release
    // Mapping to corresponding Scala types where applicable
    jdbcType match {
      case CHAR | VARCHAR | LONGVARCHAR | NCHAR | NVARCHAR | LONGNVARCHAR => "String"
      case NUMERIC | DECIMAL => "BigDecimal"
      case BIT | BOOLEAN => "Boolean"
      case TINYINT => "Byte"
      case SMALLINT => "Short"
      case INTEGER => "Int"
      case BIGINT => "Long"
      case REAL => "Float"
      case FLOAT | DOUBLE => "Double"
      case BINARY | VARBINARY | LONGVARBINARY | BLOB => "java.sql.Blob"
      case DATE => "java.sql.Date"
      case TIME => "java.sql.Time"
      case TIMESTAMP => "java.sql.Timestamp"
      case CLOB => "java.sql.Clob"
      // case ARRAY => "java.sql.Array"
      // case STRUCT => "java.sql.Struct"
      // case REF => "java.sql.Ref"
      // case DATALINK => "java.net.URL"
      // case ROWID => "java.sql.RowId"
      // case NCLOB => "java.sql.NClob"
      // case SQLXML => "java.sql.SQLXML"
      case NULL => "Null"
      case _ => "AnyRef"
    }
  }

  /** Slick code generator string extension methods. (Warning: Not unicode-safe, uses String#apply) */
  implicit class StringExtensions(val str: String){
    /** Lowercases the first (16 bit) character. (Warning: Not unicode-safe, uses String#apply) */
    final def uncapitalize: String = str(0).toString.toLowerCase + str.tail

    /**
     * Capitalizes the first (16 bit) character of each word separated by one or more '_'. Lower cases all other characters. 
     * Removes one '_' from each sequence of one or more subsequent '_' (to avoid collision).
     * (Warning: Not unicode-safe, uses String#apply)
     */
    final def toCamelCase: String
      = str.toLowerCase
           .split("_")
           .map{ case "" => "_" case s => s } // avoid possible collisions caused by multiple '_'
           .map(_.capitalize)
           .mkString("")
  }
}
