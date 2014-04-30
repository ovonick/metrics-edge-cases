#Metrics edge cases

Upon code reviewing Metrics framework some of the implementation questions were raised and as a result the following "edge case"
scenarios were developed to highlight the problems. These might be bugs in code, limitations of algorithms
or just misunderstandings and improper usages. It would be nice to get some answers.

**Implementation notes:**

- For every scenario below there is a "scheduled reporter" than executes every minute and outputs metrics in "Graphite" format.
- The "real" or "base line" 95th percentile is calculated as "values[values.length * 0.95]"

## Scenario 1

### Summary
When reporting Timer values the last reported value gets "stuck" and is being reported even if there are no
more new values are recorded for prolonged period of time. This might be confusing.
It appears that SlidingTimeWindowReservoir addresses this problem but in this case we end up hooked on "unbound" data structure.

### Simulation
There is a process that starts every 20 minutes and runs for 5-10 minutes. It performs some operation which response time
is recorded by Timer metric.

### Charts
![Response times are reported even when there is no activity](output/images/scenario1-count.png "Response times are reported even when there is no activity")

## Scenario 2

### Summary
In this scenario Timer completely misses the spike in 95th percentile response time. Also "one minute rate" is fading rather slowly
which may be confusing too.

### Simulation
There is a process that starts every 30 minutes and runs for about 20 minutes. Every time process starts it runs "normally"
recording response time of 30ms. Then "something happens" in the system and process's response time jumps to 15000ms
and is sustained on this level for the next 10 minutes. 95th percentile reported by Timer completely misses the jump in
response time and keeps reporting 30ms through out the entire lifetime of the process.

### Charts
![Timer missed spike in response time](output/images/scenario2-95thpercentile.png "Timer missed spike in response time")

Chart has logarithmic scale and that's why all zero values were replaced with 0.5. In other words 0.5 really means zero.

![Timer.getOneMinuteRate() fades slowly which may be confusing](output/images/scenario2-count.png "Timer.getOneMinuteRate() fades slowly which may be confusing")

##Scenario 3

### Summary
This scenario shows that you need to understand what you are reporting and what surprises you may run into if you don't understand implementation specifics.

1. Based on the method name itself one may decide that Timer.getOneMinuteRate() returns a rate per minute.
As a matter of fact it is more an "exponential moving average of the 5 seconds rate over one minute period".
In order to get a count of events happened within a minute one would better to use Timer.getCount() and rely on
backend system like graphite to properly display a one minute rate.
2. Reservoir sampling (which is the basis for Timer implementation) does not
do a good job in terms of accuracy. It is distribution dependent and ~25% error margin
for 95th percentile was observed on the sample set of values. Even greater error margin is observed for higher percentiles.

### Simulation
There is a precalculated set of 5000 values. They are being recorded as "timer" values. This process repeats every minute.

### Charts
![Timer.getOneMinuteRate() is not the same as count per minute](output/images/scenario3-count.png "Timer.getOneMinuteRate() is not the same as count per minute")

This initial spike to ~33000 a minute rate (went through the roof) makes little sense either.

![Reservoir sampling response time vs real response time](output/images/scenario3-95thpercentile.png "Reservoir sampling response time vs real response time")

![Reservoir sampling can be quite inaccurate. Up to 25% error margin on 95th percentile](output/images/scenario3-error-95thpercentile.png "Reservoir sampling can be quite inaccurate. Up to 25% error margin on 95th percentile")
