##### 一、登录模块

###### 1、发送短信验证码

- 生成随机验证码并存放到Redis中，其中key为业务前缀+当前手机号

###### 2、账号登录

- 校验短信验证码
    - 从Redis中取出验证码进行比对
- 通过校验后根据手机号查询用户是否存在
    - 不存在则进行创建
- 随机生成token作为key，并将用户信息作为value以hash的结构存入Redis中
- 登录成功，将验证码从Redis中删除，并向前端返回token
- 前端token放入请求头中，以便后端进行取值

###### 3、自定义拦截器

- 最先定义的拦截器只会拦截用户的登录页面等，如果用户长时间在列表页面，就会导致token的过期，因此需要定义另一个拦截所有路径的拦截器，用来查询用户并刷新token有效期，然后放行到最先定义的拦截器，用来查询ThreadLocal中的用户，存在则不拦截

- 拦截器一：（后定义的）
    - 拦截所有路径
    - 从请求头中获取token，token不存在则放行到第二个拦截器进行拦截
    - 基于token获取Redis中的用户信息，用户不存在则放行到第二个拦截器进行拦截
    - 将Redis中的hash数据转为userDTO对象并存放到ThreadLocal中
- 拦截器二：（最先定义的）
    - 查询ThreadLocal中的用户
    - 有用户则放行，否则拦截
- 总结：第一个拦截器用来存储用户到ThreadLocal，第二个拦截器根据ThreadLocal来拦截
- 在MVCconfig中进行配置
    - 按照添加的顺序执行拦截器
    - 也可以手动设置拦截器的顺序