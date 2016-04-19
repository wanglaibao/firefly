package com.firefly.db;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import javax.sql.DataSource;

import org.apache.commons.dbutils.BasicRowProcessor;
import org.apache.commons.dbutils.BeanProcessor;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.handlers.BeanHandler;
import org.apache.commons.dbutils.handlers.BeanListHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;

import com.firefly.db.annotation.Id;
import com.firefly.utils.Assert;
import com.firefly.utils.ReflectUtils;
import com.firefly.utils.StringUtils;
import com.firefly.utils.classproxy.ClassProxyFactoryUsingJavassist;
import com.firefly.utils.collection.ConcurrentReferenceHashMap;
import com.firefly.utils.function.Func2;
import com.firefly.utils.log.Log;
import com.firefly.utils.log.LogFactory;

public class JDBCHelper {

	private final static Log log = LogFactory.getInstance().getLog("firefly-system");

	protected final ConcurrentMap<Class<?>, String> idColumnNameCache = new ConcurrentReferenceHashMap<>(128);

	private final DataSource dataSource;
	private QueryRunner runner;
	private BeanProcessor defaultBeanProcessor = new DefaultBeanProcessor();

	public JDBCHelper(DataSource dataSource) {
		this.dataSource = dataSource;
		if (log.isDebugEnabled()) {
			QueryRunner queryRunner = new QueryRunner(dataSource);
			try {
				this.runner = (QueryRunner) ClassProxyFactoryUsingJavassist.INSTANCE.createProxy(queryRunner,
						(handler, originalInstance, args) -> {

							if (args != null && args.length > 0) {
								String sql = null;
								String params = null;
								for (int i = 0; i < args.length; i++) {
									Object arg = args[i];
									if (arg instanceof String) {
										sql = (String) arg;
									}

									if (arg instanceof Object[]) {
										params = Arrays.toString((Object[]) arg);
									}
								}
								log.debug("the method {} will execute SQL [ {} | {} ]", handler.method().getName(), sql,
										params);
							}

							Object ret = handler.invoke(originalInstance, args);
							return ret;
						}, null);
			} catch (Throwable t) {
				this.runner = new QueryRunner(dataSource);
				log.error("create QueryRunner proxy exception", t);
			}
		} else {
			this.runner = new QueryRunner(dataSource);
		}
	}

	public DataSource getDataSource() {
		return dataSource;
	}

	public QueryRunner getRunner() {
		return runner;
	}

	public BeanProcessor getDefaultBeanProcessor() {
		return defaultBeanProcessor;
	}

	public void setDefaultBeanProcessor(BeanProcessor defaultBeanProcessor) {
		this.defaultBeanProcessor = defaultBeanProcessor;
	}

	public <T> T queryForSingleColumn(String sql, Object... params) {
		try (Connection connection = dataSource.getConnection()) {
			return this.queryForSingleColumn(connection, sql, params);
		} catch (SQLException e) {
			log.error("get connection exception", e);
			return null;
		}
	}

	public <T> T queryForSingleColumn(Connection connection, String sql, Object... params) {
		try {
			return runner.query(connection, sql, new ScalarHandler<T>(), params);
		} catch (SQLException e) {
			log.error("query exception, sql: {}", e, sql);
			return null;
		}
	}

	public <T> T queryForObject(String sql, Class<T> t, Object... params) {
		return this.queryForObject(sql, t, defaultBeanProcessor, params);
	}

	public <T> T queryForObject(String sql, Class<T> t, BeanProcessor beanProcessor, Object... params) {
		try (Connection connection = dataSource.getConnection()) {
			return this.queryForObject(connection, sql, t, beanProcessor, params);
		} catch (SQLException e) {
			log.error("get connection exception", e);
			return null;
		}
	}

	public <T> T queryForObject(Connection connection, String sql, Class<T> t, Object... params) {
		return this.queryForObject(connection, sql, t, defaultBeanProcessor, params);
	}

	public <T> T queryForObject(Connection connection, String sql, Class<T> t, BeanProcessor beanProcessor,
			Object... params) {
		try {
			return runner.query(connection, sql, new BeanHandler<T>(t, new BasicRowProcessor(beanProcessor)), params);
		} catch (SQLException e) {
			log.error("query exception, sql: {}", e, sql);
			return null;
		}
	}

	public String getIdColumnName(Class<?> t) {
		String name = idColumnNameCache.get(t);
		if (name != null) {
			return name;
		} else {
			String newName = _getIdColumnName(t);
			if (newName == null) {
				return null;
			} else {
				String oldName = idColumnNameCache.putIfAbsent(t, newName);
				return oldName == null ? newName : oldName;
			}
		}
	}

	protected String _getIdColumnName(Class<?> t) {
		Field[] fields = t.getDeclaredFields();
		for (Field field : fields) {
			Id id = field.getAnnotation(Id.class);
			if (id != null) {
				if (StringUtils.hasText(id.value())) {
					return id.value();
				} else {
					return field.getName();
				}
			}
		}

		Method[] methods = t.getMethods();
		for (Method method : methods) {
			Id id = method.getAnnotation(Id.class);
			if (id != null) {
				if (StringUtils.hasText(id.value())) {
					return id.value();
				} else {
					return ReflectUtils.getPropertyName(method);
				}
			}
		}

		return null;
	}

	public <K, V> Map<K, V> queryForBeanMap(String sql, Class<V> t, Object... params) {
		return this.queryForBeanMap(sql, t, defaultBeanProcessor, params);
	}

	public <K, V> Map<K, V> queryForBeanMap(String sql, Class<V> t, BeanProcessor beanProcessor, Object... params) {
		String columnName = getIdColumnName(t);
		Assert.notNull(columnName);

		try (Connection connection = dataSource.getConnection()) {
			return this.queryForBeanMap(connection, sql, t, columnName, beanProcessor, params);
		} catch (SQLException e) {
			log.error("get connection exception", e);
			return null;
		}
	}

	public <K, V> Map<K, V> queryForBeanMap(Connection connection, String sql, Class<V> t, Object... params) {
		String columnName = getIdColumnName(t);
		Assert.notNull(columnName);
		return this.queryForBeanMap(connection, sql, t, columnName, defaultBeanProcessor, params);
	}

	public <K, V> Map<K, V> queryForBeanMap(Connection connection, String sql, Class<V> t, String columnName,
			BeanProcessor beanProcessor, Object... params) {
		try {
			return runner.query(connection, sql,
					new DefaultBeanMapHandler<K, V>(t, new BasicRowProcessor(beanProcessor), 0, columnName), params);
		} catch (SQLException e) {
			log.error("query exception, sql: {}", e, sql);
			return null;
		}
	}

	public <T> List<T> queryForList(String sql, Class<T> t, Object... params) {
		return this.queryForList(sql, t, defaultBeanProcessor, params);
	}

	public <T> List<T> queryForList(String sql, Class<T> t, BeanProcessor beanProcessor, Object... params) {
		try (Connection connection = dataSource.getConnection()) {
			return this.queryForList(connection, sql, t, beanProcessor, params);
		} catch (SQLException e) {
			log.error("get connection exception", e);
			return null;
		}
	}

	public <T> List<T> queryForList(Connection connection, String sql, Class<T> t, Object... params) {
		return this.queryForList(connection, sql, t, defaultBeanProcessor, params);
	}

	public <T> List<T> queryForList(Connection connection, String sql, Class<T> t, BeanProcessor beanProcessor,
			Object... params) {
		try {
			return runner.query(connection, sql, new BeanListHandler<T>(t, new BasicRowProcessor(beanProcessor)),
					params);
		} catch (SQLException e) {
			log.error("query exception, sql: {}", e, sql);
			return null;
		}
	}

	public <T> T executeTransaction(Func2<Connection, JDBCHelper, T> func) {
		try {
			Connection connection = dataSource.getConnection();
			connection.setAutoCommit(false);
			try {
				T ret = func.call(connection, this);
				connection.commit();
				return ret;
			} catch (Throwable t) {
				connection.rollback();
				log.error("the transaction exception", t);
			} finally {
				connection.close();
			}
		} catch (SQLException e) {
			log.error("get connection exception", e);
		}
		return null;
	}

}