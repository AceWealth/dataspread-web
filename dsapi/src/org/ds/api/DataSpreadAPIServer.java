package org.ds.api;

import org.model.DBHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.zkoss.zss.model.impl.sys.formula.FormulaAsyncSchedulerSimple;
import org.zkoss.zss.model.sys.formula.FormulaAsyncScheduler;

@SpringBootApplication
public class DataSpreadAPIServer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(DataSpreadAPIServer.class);
    }

	public static void main(String[] args){
        String url = "jdbc:postgresql://127.0.0.1:5432/dataspread";
        String driver = "org.postgresql.Driver";
        String userName = "dbuser";
        String password = "dbadmin";
        DBHandler.connectToDB(url, driver, userName, password);
        DBHandler.instance.initApplication();
        FormulaAsyncScheduler formulaAsyncScheduler = new FormulaAsyncSchedulerSimple();
        Thread thread = new Thread(formulaAsyncScheduler);
        thread.start();
		SpringApplication.run(DataSpreadAPIServer.class, args);
	}

}
