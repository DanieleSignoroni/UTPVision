package it.softre.thip.base.connettori.salesforce.generale;

import com.thera.thermfw.persist.*;
import java.sql.*;
import com.thera.thermfw.base.*;
import it.thera.thip.cs.*;

public class YPsnDatiSalesForceTM extends TableManager {

	public static final String ID_AZIENDA = "ID_AZIENDA";

	public static final String STATO = "STATO";

	public static final String R_UTENTE_CRZ = "R_UTENTE_CRZ";

	public static final String TIMESTAMP_CRZ = "TIMESTAMP_CRZ";

	public static final String R_UTENTE_AGG = "R_UTENTE_AGG";

	public static final String TIMESTAMP_AGG = "TIMESTAMP_AGG";

	public static final String INSTANCE_URL = "INSTANCE_URL";

	public static final String TOKEN = "TOKEN";
	
	public static final String NOME_LISTINO_PREZZI = "NOME_LISTINO_PREZZI";

	public static final String TABLE_NAME = SystemParam.getSchema("SOFTRE") + "YPSN_DATI_SALES_FORCE";

	private static TableManager cInstance;

	private static final String CLASS_NAME = it.softre.thip.base.connettori.salesforce.generale.YPsnDatiSalesForce.class.getName();

	public synchronized static TableManager getInstance() throws SQLException {
		if (cInstance == null) {
			cInstance = (TableManager)Factory.createObject(YPsnDatiSalesForceTM.class);
		}
		return cInstance;
	}

	public YPsnDatiSalesForceTM() throws SQLException {
		super();
	}

	protected void initialize() throws SQLException {
		setTableName(TABLE_NAME);
		setObjClassName(CLASS_NAME);
		init();
	}

	protected void initializeRelation() throws SQLException {
		super.initializeRelation();
		addAttribute("InstanceUrl", INSTANCE_URL);
		addAttribute("Token", TOKEN);
		addAttribute("IdAzienda", ID_AZIENDA);
		addAttribute("NomeListinoPrezzi", NOME_LISTINO_PREZZI);
		
		addComponent("DatiComuniEstesi", DatiComuniEstesiTTM.class);
		setKeys(ID_AZIENDA);

		setTimestampColumn("TIMESTAMP_AGG");
		((it.thera.thip.cs.DatiComuniEstesiTTM)getTransientTableManager("DatiComuniEstesi")).setExcludedColums();
	}

	private void init() throws SQLException {
		configure();
	}

}

