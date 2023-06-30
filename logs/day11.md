****

# 分布式锁
满足分布式系统或集群模式下多进程可见并且互斥的锁。
## Redisson可重入锁原理
### 加锁
1. 判断锁是否存在
2. 存在
	- 不存在，获取锁并添加线程标示
	- 设置锁有效期
	- 执行业务
 3. 不存在
	- 判断锁标示是否是自己
		- 不是，获取锁失败结束
		- 是，统计数加1，并刷新锁的有效期
### 解锁
1. 判断锁是否是自己
	- 否，锁已释放
	- 是，统计数-1
		- 判断锁计数是否为0
			- 否，重置有效期
			- 是，释放锁