package de.peterspace.nftdropper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Aspect
@Component
@Slf4j
@ConditionalOnExpression("${aspect.enabled:true}")
public class ExecutionTimeAdvice {

	@Around("@annotation(de.peterspace.nftdropper.TrackExecutionTime)")
	public Object executionTime(ProceedingJoinPoint point) throws Throwable {
		long startTime = System.currentTimeMillis();
		Object object = point.proceed();
		long endtime = System.currentTimeMillis();
		long elapsedTime = endtime - startTime;
		if (elapsedTime > 0) {
//			log.info(point.getSignature().getDeclaringType().getSimpleName() + "@" + Integer.toHexString(point.getTarget().hashCode()) + "." + point.getSignature().getName() + " took " + elapsedTime + "ms");
			log.info(point.getSignature().getDeclaringType().getSimpleName() + "." + point.getSignature().getName() + " took " + elapsedTime + "ms");
		}
		return object;
	}
}
