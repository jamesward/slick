package scala.slick.jdbc
package object meta{
  import scala.slick.driver.JdbcProfile
  import scala.slick.jdbc.JdbcBackend
  import scala.slick.ast.ColumnOption
  /**
   * Creates a Slick data model from jdbc meta data.
   * Foreign keys pointing out of the given tables are not included.
   * @param mTables tables to include in the model
   * @param profile JdbcProfile that was used to retrieve mTables (using a different one can lead to exceptions)
   */
  def createModel(mTables: Seq[MTable], profile: JdbcProfile)(implicit session: JdbcBackend#Session) : slick.model.Model = {
    import java.sql.DatabaseMetaData
    import scala.slick.{model => m}
    import collection.immutable.ListMap
    lazy val mTablesByMQName: Map[MQName,MTable] = mTables.map(t => t.name -> t).toMap
    lazy val mPrimaryKeysByMQName: Map[MQName,Seq[MPrimaryKey]] = mTables.map(t => t.name -> t.getPrimaryKeys.list.sortBy(_.keySeq)).toMap

    val tableNameByMQName = mTables.map(_.name).map( name =>
      name -> m.QualifiedName(name.name, schema=name.schema, name.catalog)
    ).toMap

    val columnsByTableAndName: Map[MQName,Map[String,m.Column]] = {
      def column(tableName: m.QualifiedName, column: MColumn) = {
        val mPrimaryKeys = mPrimaryKeysByMQName(column.table)
        val IntValue = "^([0-9]*)$".r
        val DoubleValue = "^([0-9*]\\.[0-9]*)$".r
        val StringValue = """^'(.+)'$""".r
        import ColumnOption._
        val c = m.Column(
          name=column.name,
          table=tableName,
          jdbcType=column.sqlType,
          nullable=column.nullable.getOrElse(true),
          // omitting the DBType as it is not portable between backends
          options = Set() ++
            (if(column.isAutoInc.getOrElse(false)) Some(AutoInc) else None) ++
            // FIXME: just ignoring other default values
            (column.columnDef.collect{
               case IntValue(value) => value.toInt
               case DoubleValue(value) => value.toDouble
               case StringValue(value) => value
               case "NULL" => None
             }.map(Default.apply)) ++
            // Add ColumnOption if single column primary key
            (if(mPrimaryKeys.size == 1) mPrimaryKeys.filter(_.column == column.name).map(_ => PrimaryKey) else Set())
        )
        c
      }

      mTablesByMQName.mapValues( t => ListMap(t.getColumns.list.sortBy(_.ordinalPosition).map(c => c.name -> column(tableNameByMQName(t.name),c)):_*))
    }

    def table(mTable: MTable) = {
      val tableName = tableNameByMQName(mTable.name)
      val columns = columnsByTableAndName(mTable.name).values.toSeq
      val columnsByName: Map[String,m.Column] = columns.map(c => c.name -> c).toMap
      
      def primaryKey(mPrimaryKeys:Seq[MPrimaryKey]) = {
        // single column primary keys excluded in favor of PrimaryKey column option
        if(mPrimaryKeys.size <= 1) None else Some(
          m.PrimaryKey(
            mPrimaryKeys.head.pkName,
            tableName,
            mPrimaryKeys.map(_.column).map(columnsByName)
          )
        )
      }
      def foreignKeys(mForeignKeys:Seq[MForeignKey]) = {
        mForeignKeys
          // remove foreign keys pointing to tables which were not included
          .filter(fk => mTablesByMQName.isDefinedAt(fk.pkTable))
          .groupBy(fk => (fk.pkTable,fk.fkName,fk.pkName,fk.fkTable))
          .toSeq
          .sortBy{case (key,_) => (key._1.name,key._2,key._3,key._4.name)}
          .map(_._2.sortBy(_.keySeq)) // respect order
          .map{ fks =>
            val fk = fks.head
            assert(tableName == tableNameByMQName(fk.fkTable))
            val fkColumns = fks.map(_.fkColumn).map(columnsByName)
            val pkColumns = fks.map(_.pkColumn).map(columnsByTableAndName(fk.pkTable))
            assert(fkColumns.size == pkColumns.size)
            m.ForeignKey(
              fk.fkName,
              tableName,
              fkColumns,
              tableNameByMQName(fk.pkTable),
              pkColumns,
              fk.updateRule,
              fk.deleteRule
            )
          }
      }
      def indices(mIndexInfo: Seq[MIndexInfo]) = {
        mIndexInfo
          // filter out unnecessary tableIndexStatistic (we can safely call .get later)
          .filter(_.indexType != DatabaseMetaData.tableIndexStatistic)
          .groupBy(_.indexName)
          .toSeq
          .sortBy(_._1)
          .map(_._2.sortBy(_.ordinalPosition)) // respect order
          .map{ mIndices =>
            val idx = mIndices.head
            m.Index(
              idx.indexName,
              tableName,
              mIndices.map(_.column.get).map(columnsByName),
              !idx.nonUnique
            )
          }
      }

      val mPrimaryKeys = mPrimaryKeysByMQName(mTable.name)
      val fks = foreignKeys(mTable.getImportedKeys.list)
      m.Table(
        tableName,
        columns,
        primaryKey(mPrimaryKeys),
        fks,
        // indices not including primary key and table statistics
        indices(mTable.getIndexInfo().list)
          .filter{ 
            // filter out foreign key index
            case idx if !idx.unique => !fks.exists(_.referencingColumns.toSet == idx.columns.toSet)
            // filter out primary key index
            case idx  => mPrimaryKeys.isEmpty || mPrimaryKeys.map(_.column).toSeq != idx.columns.map(_.name).toSeq
          }
      )
    }

    m.Model( mTables.sortBy(_.name.name).map(table) )
  }
}
