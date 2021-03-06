package org.elasticsearch.spark.integration;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.DatatypeConverter;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.elasticsearch.hadoop.mr.RestUtils;
import org.elasticsearch.hadoop.util.TestSettings;
import org.elasticsearch.hadoop.util.TestUtils;
import org.elasticsearch.spark.sql.api.java.JavaEsSparkSQL;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.google.common.collect.ImmutableMap;

import static org.junit.Assert.*;

import static org.elasticsearch.hadoop.cfg.ConfigurationOptions.*;

import static org.hamcrest.Matchers.*;

import static scala.collection.JavaConversions.*;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class AbstractJavaEsSparkSQLTest implements Serializable {

	private static final transient SparkConf conf = new SparkConf()
			.setAll(propertiesAsScalaMap(TestSettings.TESTING_PROPS))
			.setAppName("estest");
	
	private static transient JavaSparkContext sc = null;
	private static transient SQLContext sqc = null;

	@BeforeClass
	public static void setup() {
		sc = new JavaSparkContext(conf);
		sqc = new SQLContext(sc);
	}

	@AfterClass
	public static void clean() throws Exception {
		if (sc != null) {
			sc.stop();
			// wait for jetty & spark to properly shutdown
			Thread.sleep(TimeUnit.SECONDS.toMillis(2));
		}
	}

	@Test
	public void testBasicRead() throws Exception {
        Dataset<Row> dataset = artistsAsDataset();
        assertTrue(dataset.count() > 300);
        dataset.createOrReplaceTempView("datfile");
        assertEquals(5, ((Object[]) dataset.take(5)).length);
        Dataset<Row> results = sqc
				.sql("SELECT name FROM datfile WHERE id >=1 AND id <=10");
        assertEquals(10, ((Object[]) results.take(10)).length);
	}

	@Test
    public void testEsDataset1Write() throws Exception {
        Dataset<Row> dataset = artistsAsDataset();

		String target = "sparksql-test/scala-basic-write";
        JavaEsSparkSQL.saveToEs(dataset, target);
		assertTrue(RestUtils.exists(target));
		assertThat(RestUtils.get(target + "/_search?"), containsString("345"));
	}

	@Test
    public void testEsDataset1WriteWithId() throws Exception {
        Dataset<Row> dataset = artistsAsDataset();

		String target = "sparksql-test/scala-basic-write-id-mapping";
        JavaEsSparkSQL.saveToEs(dataset, target,
				ImmutableMap.of(ES_MAPPING_ID, "id"));
		assertTrue(RestUtils.exists(target));
		assertThat(RestUtils.get(target + "/_search?"), containsString("345"));
		assertThat(RestUtils.exists(target + "/1"), is(true));
	}

    @Test
    public void testEsSchemaRDD1WriteWithMappingExclude() throws Exception {
        Dataset<Row> dataset = artistsAsDataset();

        String target = "sparksql-test/scala-basic-write-exclude-mapping";
        JavaEsSparkSQL.saveToEs(dataset, target,
                ImmutableMap.of(ES_MAPPING_EXCLUDE, "url"));
        assertTrue(RestUtils.exists(target));
        assertThat(RestUtils.get(target + "/_search?"), not(containsString("url")));
    }
    
	@Test
    public void testEsDataset2Read() throws Exception {
		String target = "sparksql-test/scala-basic-write";

        // Dataset<Row> dataset = JavaEsSparkSQL.esDF(sqc, target);
        Dataset<Row> dataset = sqc.read().format("es").load(target);
        assertTrue(dataset.count() > 300);
        String schema = dataset.schema().treeString();
		System.out.println(schema);
		assertTrue(schema.contains("id: long"));
		assertTrue(schema.contains("name: string"));
		assertTrue(schema.contains("pictures: string"));
		assertTrue(schema.contains("time: long"));
		assertTrue(schema.contains("url: string"));

        // Dataset.take(5).foreach(println)

        dataset.registerTempTable("basicRead");
        Dataset<Row> nameRDD = sqc
				.sql("SELECT name FROM basicRead WHERE id >= 1 AND id <=10");
		assertEquals(10, nameRDD.count());
	}

    private Dataset<Row> artistsAsDataset() throws Exception {
        // don't use the sc.textFile as it pulls in the Hadoop madness (2.x vs 1.x)
        Path path = Paths.get(TestUtils.sampleArtistsDatUri());
        // because Windows... 
        List<String> lines = Files.readAllLines(path, StandardCharsets.ISO_8859_1);
		JavaRDD<String> data = sc.parallelize(lines);

		StructType schema = DataTypes
				.createStructType(new StructField[] {
						DataTypes.createStructField("id", DataTypes.IntegerType, false),
						DataTypes.createStructField("name", DataTypes.StringType, false),
						DataTypes.createStructField("url", DataTypes.StringType, true),
						DataTypes.createStructField("pictures", DataTypes.StringType, true),
						DataTypes.createStructField("time", DataTypes.TimestampType, true) });

		JavaRDD<Row> rowData = data.map(new Function<String, String[]>() {
			@Override
			public String[] call(String line) throws Exception {
				return line.split("\t");
			}
		}).map(new Function<String[], Row>() {
			@Override
			public Row call(String[] r) throws Exception {
				return RowFactory.create(Integer.parseInt(r[0]), r[1], r[2], r[3],
						new Timestamp(DatatypeConverter.parseDateTime(r[4]).getTimeInMillis()));
			}
		});

        return sqc.createDataFrame(rowData, schema);
	}
}