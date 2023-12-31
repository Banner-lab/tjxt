#### 1 实时榜单生成
在这个功能中，我们会记录当月用户的积分情况形成一个排行榜。在积分业务中我们有了一张points_record表，会记录用户每次获得积分的情况。那么一个用户一个月内就可以产生多条记录：比如每日签到、课程学习，解答问题等，那么如果想要形成排行榜，那么在查询数据库时，就需要先对用户分组，再对积分求和。如前文所述，一个用户一个月内就可以产生数十甚至上百条积分记录，当我们的应用使用人数达到百万规模，假设平均每个用户每个月产生了100条记录，乘以用户规模，那么积分的数据记录就将数以亿计。
为此我们不使用数据库存储实时榜单数据，转而使用Redis中的SortedSet这一结构存储用户积分数据，而且Redis作为k-v内存数据库，查询性能优秀。我们将每个赛季作为一个key，value为用户id，将用户积分作为排序用的score，需要计算排名时，只需要按照赛季查询出用户积分数据，再累加分值比他高的用户数量即可。

##### 1.1 SortedSet常用命令
> zadd key member score

向名为key的zset添加元素member,score用于排序，如果元素已经存在，根据score更新元素顺序

> zrem key member

删除名为key的zset中的元素

>   zincrby key increment member 

如果在名称为Key的zset中已经存在member,则该元素的score增加increment,否则向集合中添加该元素，score值为increment

> zrank key 0 -1 withscores

 zrank key 0 -1 withscores：返回名为key的zset中member元素的排名（按score从小到大排序）
 
> zrevrank key 0 -1 withscores

返回名称为key的zset中member元素的排名（逆序）

##### 1.2生成实时榜单
在用户每次积分变更时，累加积分到Redis的SortedSet中，以赛季时间为key，用户id为member，score为用户积分，每次用户新增积分，就累加到score中

#### 2 历史排行榜
在天机学堂的项目中，每一个月就是一个新的赛季，因此每到每个月初，就会进入一个新的赛季，新赛季用户积分就应该清零，同时将历史榜单数据持久化到数据库中。但是，开篇我们就提到了假设用户规模达到了百万级，这就意味着每个赛季榜单数据达到了几百万条，随着时间推移，历史赛季越来越多，数据量将会非常庞大，因此我们不可能将这些历史榜单数据存储到同一张表。

##### 2.1 海量数据存储策略
对于数据库的海量数据存储，常见方案：

###### 2.1.1 分区
表分区是一种数据存储方案，可以解决单表数据较多的问题。MySQL5.1开始支持。
数据库的表数据最终都是持久化到了硬盘中，对于InnoDB引擎来说，一张表的数据在磁盘上对应一个ibd文件，如果表数据过多，就会导致文件体积过大。文件就会跨越多个磁盘分区，数据检索速度就会非常慢。
表分区就是按照某种规则，把表数据对应的ibd文件拆分成多个文件存储。从物理上看，一张表的数据被拆到多个表文件存储；逻辑上看，它们对外表现是一张表。
优点：
* 可以存储更多数据，突破单表上限。甚至可以存储到不同磁盘，突破磁盘上限
* 查询时可以按照规则只检索某一个文件，提高查询效率
* 数据统计时，可以多文件并行统计，最后汇总结果，提高统计效率
* 对于一些历史数据，如果不需要时，可以直接删除分区文件，提高删除效率

###### 2.1.2 分表
**分表**是一种表设计方案，由开发者在创建表时按照自己的业务需求拆分表。也就是说这是开发者自己对表的处理，与数据库无关。
**水平分表**：表结构不变，仅仅是每张表数据不同
**垂直分表**：将一张表按照字段拆分成两个或以上张表
分表方案相对于分区方案拆分方式更加灵活，而且可以解决单表字段过多的问题，缺点是增删改查时，需要自己判断访问哪张表，垂直拆分还会导致事务问题及数据关联问题，原本一张表的操作，变为多表操作。

###### 2.1.3 分库和集群
微服务中，按照项目模块，每个数据使用独立的数据库，因此每个库的表是不同的，这种分库模式称为垂直分库。
同时为了保证单节点的高可用性，会为数据库建立主从集群，主节点向从节点同步数据。两者结构一样，可以看作水平分库。
优点：
1. 解决了海量数据存储问题，突破了单机存储瓶劲
2. 提高了并发能力，突破了单机性能瓶劲
3. 避免了单点故障
缺点：
- 成本非常高
- 数据聚合统计麻烦
- 主从同步一致性问题
- 分布式事务问题

#### 3 分布式任务调度
一般定时任务实现原理，一般定时任务中会有两个组件：
* 任务：要执行的代码
* 任务触发器：基于定义好的规则触发任务
在多实例部署的时候，每个启动的服务实例都会有自己的任务触发器，这样就会导致各个实例各自运行，无法统一控制
所以在多实例部署的环境下，我们自然想到可以将任务触发器提取到各个服务实例之外，去做统一的触发，统一的调度，但是，大多数分布式调度组件有一个任务调度器来控制任务执行。

#### 3.1 XXL-JOB
xxl-job分为两部分：
* 执行器：在微服务中引入一个xxl-job依赖，就可以通过配置创建一个执行器，负责与xxl-job调度中心交互，执行本地任务
* 调度中心：一个独立服务，负责管理执行器、管理任务、任务执行的调度、任务结果和日志收集


