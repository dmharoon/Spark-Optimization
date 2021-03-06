package org.iiitb.optimization;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.mllib.linalg.BLAS;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.optimization.Gradient;
import org.apache.spark.mllib.optimization.LeastSquaresGradient;
import org.apache.spark.mllib.optimization.Optimizer;

import org.apache.spark.rdd.RDD;

import scala.Tuple2;

public class ConjugateGradientOptimizer implements Optimizer 
{

	private static final long serialVersionUID = 1L;
	private double lineConvTol;
	private int numIteration;
	private int lineIteration;
	private Gradient gd;
	private int noCalls;
	
	
	
	public int getNoCalls() {
		return noCalls;
	}

	public void setNoCalls(int noCalls) {
		this.noCalls = noCalls;
	}

	public ConjugateGradientOptimizer(double lineConvTol, int numIteration,int lineIteration)
	{
		this.lineConvTol = lineConvTol;
		this.numIteration = numIteration;
		this.lineIteration = lineIteration;
		this.noCalls = 0;
		gd = new LeastSquaresGradient();
		
		
	}

	@Override
	public Vector optimize(RDD<Tuple2<Object, Vector>> data, Vector w) 
	{
		int i = 1;
		JavaRDD<Tuple2<Object, Vector>> da = data.toJavaRDD();
		JavaSparkContext jsc = new JavaSparkContext(da.context());
		final Broadcast<Double> sz = jsc.broadcast((double)da.count());
		Vector d= null;
		Vector currGrad = null;
		Vector nextGrad = null;
		Vector wOld = null;
		double beta = 0.0;
		double nextAlpha = 0.0;
		
		
		while(i <= numIteration){
			
			
			//first iteration
			 if(d == null){
				 currGrad = getGradient(da, w, sz, jsc);
				 double temp[] = new double[currGrad.size()];
				 d = Vectors.dense(temp);
				 BLAS.copy(currGrad, d);
				 BLAS.scal(-1.0, d);
			 }
			 
			 nextAlpha = strongWolfe(da, w, wOld, sz, jsc, currGrad, d);
				 
			 System.out.println("alpha for iteration "+i+" is "+nextAlpha);
			 
			 //calculate the new parameter
			 double tempW[] = new double[currGrad.size()];
			 Vector newW = Vectors.dense(tempW);
			 BLAS.copy(w, newW);
			 BLAS.axpy(nextAlpha, d, newW);
			 System.out.println("The value after "+ i +" iteration is"+w.toString());
			 
			 //convergence check
			 if(isConverged(w, newW, 0.001))
				 return newW;
			 
			 
			 //calculate new beta and di+1
			 wOld = w;
			 w = newW;
			 nextGrad = getGradient(da, w, sz, jsc); 
			 //calculate the difference between new gradient and old gradient 
			 BLAS.scal(-1.0, currGrad);
			 BLAS.axpy(1.0, nextGrad, currGrad);
			 beta = BLAS.dot(nextGrad, currGrad) / BLAS.dot(d,currGrad);
			 System.out.println("beta value at "+i+" iteration is "+beta);
			 
			
			 BLAS.scal(beta, d);
			 BLAS.axpy(-1.0,nextGrad,d);
			 currGrad = nextGrad;
		
	
			i +=1;
		}
		
		return w;
		
	}
	
	private boolean isConverged(Vector oldW, Vector newW,double con)
	{
		BLAS.axpy(-1.0, newW, oldW);
		double diff = norm(oldW);
		
		return (diff <con * Math.max(norm(newW), 1.0));
	}
	
	private double norm(Vector v)
	{
		double w[] = v.toArray();
		double sum = 0.0;
		for(int i=0; i<w.length; i++)
			sum += w[i]*w[i];
		return Math.sqrt(sum);
	}
	
	
	public Vector getGradient(JavaRDD<Tuple2<Object, Vector>> da, Vector w, final Broadcast<Double> sz,
							JavaSparkContext jsc)
	{
		final Broadcast<Vector> bw = jsc.broadcast(w);
		noCalls++;
		//compute the gradient vector of all the rows
		JavaRDD<Vector> gradRDD = da.map(new Function<Tuple2<Object,Vector>,Vector>() {
			public Vector call(Tuple2<Object, Vector> in)
			{
				Vector features = in._2;
				Tuple2<Vector,Object> grad = gd.compute(features, (Double)in._1, bw.getValue());
				BLAS.scal(1.0/sz.getValue(), grad._1);
				
				return grad._1;
			}
		});
		
		JavaPairRDD<Integer,Double> expandedGradient = gradRDD.flatMapToPair(new PairFlatMapFunction<Vector,Integer,Double>() {

			@Override
			public Iterable<Tuple2<Integer, Double>> call(Vector arg0) throws Exception 
			{
				double vec[] = arg0.toArray();
				List<Tuple2<Integer,Double>> lis = new ArrayList<Tuple2<Integer,Double>>();
				for(int i=0; i<vec.length; i++)
					lis.add(new Tuple2<Integer, Double>(i,vec[i]));
				return lis;
			}
		
			
		});
		
		 JavaPairRDD<Integer,Double> r = expandedGradient.reduceByKey(new Function2<Double, Double, Double>() {
			
			@Override
			public Double call(Double arg0, Double arg1) throws Exception {
				// TODO Auto-generated method stub
				return arg0 + arg1;
			}
		});
		 
		 List<Tuple2<Integer, Double>>  t= r.collect();
		 double temp[] = new double[t.size()];
		 
		 int k=0;
		 for(Tuple2<Integer, Double> tu:t)
			 temp[k++] = tu._2;
		 
		 Vector newGrad = Vectors.dense(temp);
		return newGrad;
	}
	
	public double secantLineSearch(JavaRDD<Tuple2<Object, Vector>> da, 
									JavaSparkContext jsc, Vector w,
									Vector d, final Broadcast<Double> sz)
	{
		double alphaOld = 0.0;
		double alphaCurr = 1;
		double alphaNew = 0.0;
		
		double gOld = computeG(da, jsc, w, d, sz, alphaOld);
		double gCurr =0.0;
		int i = 1;
		
		while(i <= lineIteration){
			
			gCurr = computeG(da, jsc, w, d, sz, alphaCurr);
			alphaNew = alphaCurr - gCurr*((alphaCurr-alphaOld) / (gCurr-gOld));
			
			if(alphaNew < 0)
				return 0.0001;
			
			if(Math.abs(alphaNew - alphaCurr) <= lineConvTol)
				return alphaNew;
			
			alphaOld = alphaCurr;
			alphaCurr = alphaNew;
			gOld = gCurr;
			
			i++;
		}

		return alphaCurr;
	}
	
	
	public double computeG(JavaRDD<Tuple2<Object, Vector>> da, 
			JavaSparkContext jsc, Vector w,
			Vector d, final Broadcast<Double> sz,double alpha)
	{
		Vector temp = w.copy();
		BLAS.axpy(alpha, d, temp);
		Vector grad = getGradient(da, temp, sz, jsc);
		
		return BLAS.dot(d, grad);
	}
	
	public double getLoss(JavaRDD<Tuple2<Object, Vector>> da, Vector w, final Broadcast<Double> sz,
			JavaSparkContext jsc)
	{
		final Broadcast<Vector> bw = jsc.broadcast(w);
		noCalls++;
		//compute the gradient vector of all the rows
		JavaRDD<Double> gradRDD = da.map(new Function<Tuple2<Object,Vector>,Double>() {
			public Double call(Tuple2<Object, Vector> in)
			{
				Vector features = in._2;
				Tuple2<Vector,Object> grad = gd.compute(features, (Double)in._1, bw.getValue());
				BLAS.scal(1.0/sz.getValue(), grad._1);
				double loss = (Double)grad._2;
				return loss * (1/sz.getValue());
			}
		});
		
		double totLoss = gradRDD.reduce(new Function2<Double, Double, Double>() {

			@Override
			public Double call(Double arg0, Double arg1) throws Exception {
				return arg0+arg1;
			}
			
		});
		
		return totLoss;
		
		
	}
	
	public double getArmigo(JavaRDD<Tuple2<Object, Vector>> da, Vector w, final Broadcast<Double> sz,
			JavaSparkContext jsc,Vector currGrad,
			Vector d)
	{
		double fk = getLoss(da, w, sz, jsc);
		double alpha = 1.0;
		double r = 0.5;
		
		double c1 = 0.0001;
		double temp = BLAS.dot(currGrad, d);
		double vrhs = alpha*c1*temp;
		
		Vector tempLHS = d.copy();
		BLAS.scal(alpha, tempLHS);
		BLAS.axpy(1.0, w, tempLHS);
		double lhs = getLoss(da, tempLHS, sz, jsc);
		
		while(lhs > fk+vrhs)
		{
			alpha = r *alpha;
			
			tempLHS = d.copy();
			BLAS.scal(alpha, tempLHS);
			BLAS.axpy(1.0, w, tempLHS);
			lhs = getLoss(da, tempLHS, sz, jsc);
			
			vrhs = alpha*c1*temp;
		}
		return alpha;
	}
	
	
	/**
	 * This function implements the strong Wolfe Line search
	 */
	
	public double strongWolfe(JavaRDD<Tuple2<Object, Vector>> da, Vector w, 
			Vector wOld, final Broadcast<Double> sz,
			JavaSparkContext jsc,Vector currGrad,
			Vector d)
	{
		double c1 = 0.0001;
		double c2 = 0.5;
		
		double alpha0 = 0.0;
		double phi0 = getLoss(da, w, sz, jsc);
		double phiA0 = 0.0;
		double phiA1 = 0.0;
		double derPhi0 = BLAS.dot(d, currGrad);
		double alpha1 = 0.0;
		double alpha2 = 0.0;
		
		if(wOld != null && derPhi0 != 0)
			alpha1 = Math.min(1.0, 2*1.01*(phi0-getLoss(da, wOld, sz, jsc))/derPhi0);
		else
			alpha1 = 1.0;
		
		if(alpha1 < 0)
			alpha1 = 1.0;
		
		phiA0 = phi0;
		
		//some optimization can be done
		Vector temp = d.copy();
		BLAS.scal(alpha1, temp);
		BLAS.axpy(1.0, w, temp);
		
		phiA1 = getLoss(da, temp, sz, jsc);
		
		int maxIter = 10;
		int i = 1;
		
		while(i <= maxIter){
			
			if(phiA1 > phi0 + c1*alpha1*derPhi0 ||
			  (phiA1 >= phiA0 && i > 1)){
				return zoom(da, w, sz, jsc, currGrad, d, alpha0, alpha1,phi0,derPhi0,phiA0,phiA1);
			}
			
			double derPhiA1 = computeG(da, jsc, w, d, sz, alpha1);
			if(Math.abs(derPhiA1) <= -c2 * derPhi0)
				return alpha1;
			
			if(derPhiA1 >=0)
				return zoom(da, w, sz, jsc, currGrad, d, alpha1, alpha0,phi0,derPhi0,phiA1,phiA0);
			
			alpha2 = 2*alpha1;
			alpha0 = alpha1;
			alpha1 = alpha2;
			phiA0 = phiA1;
			
			//some optimization can be done
			temp = d.copy();
			BLAS.scal(alpha1, temp);
			BLAS.axpy(1.0, w, temp);
			phiA1 = getLoss(da, temp, sz, jsc);
			i += 1;
		}
		
		return alpha1;
	}
	
	public double zoom(JavaRDD<Tuple2<Object, Vector>> da, Vector w, final Broadcast<Double> sz,
			JavaSparkContext jsc,Vector currGrad,
			Vector d,double alphaLow, double alphaHigh,
			double phi0,double derPhi0,
			double phiAL,double phiAH)
	{
		double c1 = 0.0001;
		double c2 = 0.1;
		double alphaj = 0.0;
		double phiAlphaj = 0.0;
		double derPhiAlphaj = 0.0;
		Vector temp = null;
		int maxIter = 50;
		int i=1;
		while(true){
			
			alphaj = (alphaLow+alphaHigh)/2;
			
			//some optimization can be done
			temp = d.copy();
			BLAS.scal(alphaj, temp);
			BLAS.axpy(1.0, w, temp);
			phiAlphaj = getLoss(da, temp, sz, jsc);
			
			if(phiAlphaj > phi0 + c1*alphaj*derPhi0 ||
			  phiAlphaj >= phiAL){
				alphaHigh = alphaj;
				phiAH = phiAlphaj;
			}
			
			else{
				derPhiAlphaj = computeG(da, jsc, w, d, sz, alphaj);
				if(Math.abs(derPhiAlphaj) <= -c2 * derPhi0)
					return alphaj;
				if(derPhiAlphaj*(alphaHigh-alphaLow) >= 0){
					alphaHigh = alphaLow;
					phiAH = phiAL;
				}
				alphaLow = alphaj;
				phiAL = phiAlphaj;
			}
			i++;
			if(i > maxIter)
				return alphaj;
		}
		
		
	}
}
