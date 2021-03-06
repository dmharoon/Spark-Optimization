package org.iiitb.driver;

import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.apache.spark.mllib.util.MLUtils;
import org.iiitb.optimization.ConjugateGradientOptimizer;
import org.iiitb.optimization.SimpleGradientOptimizer;

import scala.Tuple2;



public class Main2 
{
	public static void main(String args[])
	{
		SparkConf sc = new SparkConf();
		sc.setAppName("Gradient Demo");
		
		SparkContext jsc = new SparkContext(sc);
		
		JavaRDD<LabeledPoint> la = MLUtils.loadLibSVMFile(jsc, args[0]).toJavaRDD();
		
		JavaRDD<Tuple2<Object, Vector>> dataset = la.map(new Function<LabeledPoint, Tuple2<Object,Vector>>() {

			public Tuple2<Object, Vector> call(LabeledPoint arg0) throws Exception {
				
				return new Tuple2<Object, Vector>(arg0.label(),arg0.features());
			}
		
		});
		
		ConjugateGradientOptimizer op = new ConjugateGradientOptimizer(0.001, 50,10);
		Vector w = Vectors.dense(new double[]{0.0,0.0,0.0}); 
		w = op.optimize(JavaRDD.toRDD(dataset), w);
		System.out.println(op.getNoCalls());
		
		
		
	}
}
