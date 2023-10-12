package it.softre.thip.base.connettori.salesforce;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.ws.rs.core.MediaType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.JsonObject;
import com.thera.thermfw.batch.BatchRunnable;
import com.thera.thermfw.persist.ConnectionManager;
import com.thera.thermfw.persist.KeyHelper;
import com.thera.thermfw.persist.PersistentObject;

import it.softre.thip.base.connettori.api.YApiRequest.Method;
import it.softre.thip.base.connettori.salesforce.generale.YPsnDatiSalesForce;
import it.softre.thip.base.connettori.salesforce.tabelle.YClientiInseriti;
import it.softre.thip.base.connettori.salesforce.tabelle.YOrdiniInseriti;
import it.softre.thip.base.connettori.salesforce.tabelle.YOrdiniInseritiTM;
import it.softre.thip.base.connettori.utils.YApiManagement;
import it.thera.thip.api.client.ApiResponse;
import it.thera.thip.base.articolo.Articolo;
import it.thera.thip.base.azienda.Azienda;
import it.thera.thip.base.cliente.ClienteVendita;
import it.thera.thip.base.cliente.ClienteVenditaTM;
import it.thera.thip.base.documenti.StatoAvanzamento;
import it.thera.thip.vendite.ordineVE.OrdineVendita;
import it.thera.thip.vendite.ordineVE.OrdineVenditaRigaPrm;

public class YEsportatoreOpportunita extends BatchRunnable{

	public String endpoint = null;

	public String id = "Opportunity";

	public String idRows = "OpportunityLineItem";

	public String apiPath = null;

	public String idPricebook2 = null;

	@SuppressWarnings("unchecked")
	@Override
	protected boolean run() {
		boolean ret = true;
		writeLog("*** ESPORT ORDINI VENDITA - OPPORTUNITA SALES FORCE ***");
		YPsnDatiSalesForce psnDati = YPsnDatiSalesForce.getCurrentPersDatiSalesForce(Azienda.getAziendaCorrente());
		if(psnDati != null) {
			endpoint = psnDati.getInstanceUrl() + "/sobjects/";
			apiPath = psnDati.getToken();
			String idListino = psnDati.getNomeListinoPrezzi().replace(" ", "+");
			idPricebook2 = codificaListinoTestata(idListino);
			List<YOrdiniInseriti> lista = getListaOrdiniVenditaDaEsportare();
			for (Iterator<YOrdiniInseriti> iterator = lista.iterator(); iterator.hasNext();) {
				YOrdiniInseriti yOrdiniInseriti = (YOrdiniInseriti) iterator.next();
				try {
					OrdineVendita ordineVendita = (OrdineVendita)
							OrdineVendita.elementWithKey(OrdineVendita.class,
									yOrdiniInseriti.getKey(), PersistentObject.NO_LOCK);
					if(ordineVendita != null) {
						writeLog("--------- Processo l'ordine : "+ordineVendita.getIdNumeroOrdIC()+" ---------");
						String idClienteSalesFroce = getIdSalesForceCliente(ordineVendita.getIdAnagrafico());
						if(idClienteSalesFroce != null) {
							writeLog("L'id dell'account e' : "+idClienteSalesFroce);
							String json = getJSONAdd(ordineVendita, idClienteSalesFroce);
							if(!yOrdiniInseriti.isProcessato()) {
								writeLog("Cerco di inserire l'ordine");
								//solo se non ce' pero
								if(!esisteGia(yOrdiniInseriti.getIdSalesForce())) {
									//insert
									ApiResponse response = YApiManagement.callApi(endpoint+id, Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
									if(response.success()) {
										String opportunityId = (String) response.getBodyAsJSONObject().get("id");
										ApiResponse read = YApiManagement.callApi(endpoint+id+"/"+opportunityId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
										if(read.success()) {
											//inserire le righe ora
											Iterator<OrdineVenditaRigaPrm> iterRighe = ordineVendita.getRighe().iterator();
											while(iterRighe.hasNext()) {
												OrdineVenditaRigaPrm riga = iterRighe.next();
												writeLog("Processo la riga ordine vendita : "+riga.getKey());	
												String productId = controllaPresenzaArticolo(riga.getIdArticolo());
												if(productId == null) {
													productId = inserisciProdotto(riga.getArticolo());
													inserisciRigaListino(productId, riga.getArticolo());
												}
												String jsonRiga = getOpportunityLineItemJSON(riga,opportunityId,productId);
												insertOpportunityLineItem(jsonRiga,riga);
											}
											writeLog("Ordine inserito correttamente sotto forma di Opportunita' ");
											writeLog("Flaggo come processato l'ordine e salvo il record ");
											yOrdiniInseriti.setIdSalesForce(opportunityId);
											yOrdiniInseriti.setProcessato(true);
											if(yOrdiniInseriti.save() >= 0) {
												writeLog("Record nella tabella di appoggio salvato correttamente");
												ConnectionManager.commit();
											}else {
												writeLog("Vi sono stati errori nel salvataggio, ri-processare l'ordine ");
												ConnectionManager.rollback();
											}
										}
									}else {
										writeLog("Si sono verificati errori nella chiamata \n "+response.getBodyAsString());
									}
								}
							}
						}else {
							writeLog("**!! In Sales Force non esiste un account per il cliente :"+ordineVendita.getIdCliente()+ " !!**");
						}
						writeLog("--------- Ho finito di processare l'ordine : "+ordineVendita.getIdNumeroOrdIC()+" ---------");
					}else {
						writeLog(" **!! L'ordine "+yOrdiniInseriti.getKey()+" non esiste piu' in panthera !!**");
						writeLog("Cancello anche il record nella tabella di appoggio ");
						int del = yOrdiniInseriti.delete();
						if(del > 0) {
							writeLog("Record nella tabella di appoggio cancellato correttamente");
							ConnectionManager.commit();
						}else {
							writeLog("Vi sono stati errori nella cancellazione, si consiglia di procedere a mano \n chiave : "+yOrdiniInseriti.getKey());
							ConnectionManager.rollback();
						}
					}

				} catch (SQLException e) {
					ret = false;
					e.printStackTrace();
				} catch (JSONException e) {
					ret = false;
					e.printStackTrace();
				}
			}
		}else {
			ret = false;
			writeLog("--- Non e' stata definita la personalizzazione dati Sales Force, parametro obbligatorio ");
		}
		writeLog("*** TERMINE ESPORT ORDINI VENDITA - OPPORTUNITA SALES FORCE ***");
		return ret;
	}

	protected String getJSONRigaListino(String productId, Articolo articolo) {
		JsonObject json = new JsonObject();
		json.addProperty("Pricebook2Id", idPricebook2);
		json.addProperty("Product2Id", productId);
		json.addProperty("UnitPrice", "0");
		json.addProperty("IsActive", true);
		return json.toString();
	}

	protected void inserisciRigaListino(String productId, Articolo articolo) {
		String json = getJSONRigaListino(productId,articolo);
		try {
			ApiResponse response = YApiManagement.callApi(endpoint+"PriceBookEntry", Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
			if(response.success()) {
				String priceBookEntryId = (String) response.getBodyAsJSONObject().get("id");
				ApiResponse read = YApiManagement.callApi(endpoint+"PriceBookEntry"+"/"+priceBookEntryId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
				if(read.success()) {
					writeLog("Riga listino inserita correttamente");
				}
			}else {
				writeLog("Impossibile inserire riga listino: "+response.getBodyAsString());
			}
		}catch (JSONException e) {
			e.printStackTrace();
		}
	}

	protected boolean esisteGia(String idSalesForce) {
		ApiResponse read = YApiManagement.callApi(endpoint+id+"/"+idSalesForce, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
		if(read.success()) {
			return true;
		}
		return false;
	}

	protected String codificaListinoTestata(String idListino) {
		Map<String,String> parameters = new HashMap<String, String>();
		String query = "SELECT+Id+FROM+"+YFunzioni.LISTINO_TES+"+WHERE+Name='"+idListino+"'";
		parameters.put("q", query);
		ApiResponse resp = YApiManagement.callApi(endpoint.replace("sobjects", "query"), Method.GET, MediaType.APPLICATION_JSON, null, parameters, null,apiPath);
		if(resp.success()) {
			try {	
				JSONObject body = resp.getBodyAsJSONObject();
				JSONArray records = (JSONArray) body.get("records");
				for (int i = 0; i < records.length(); i++) {
					JSONObject obj = records.getJSONObject(i);
					if(obj.get("Id") != null) {
						return obj.getString("Id");
					}else {

					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}else {
			writeLog(resp.getBodyAsString());
		}
		return null;
	}

	protected String inserisciProdotto(Articolo articolo) {
		String id = null;
		try {
			String json = getProductJSON(articolo);
			ApiResponse response = YApiManagement.callApi(endpoint+YFunzioni.PRODUCT, Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
			if(response.success()) {
				id = (String) response.getBodyAsJSONObject().get("id");
				ApiResponse read = YApiManagement.callApi(endpoint+YFunzioni.PRODUCT+"/"+id, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
				if(read.success()) {
					writeLog("Articolo inserito in Sales Force correttamente");
				}else {
					id = null;
				}
			}else {
				writeLog("Impossibile inserire l'articolo: "+response.getBodyAsString());
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return id;
	}

	public String getProductJSON(Articolo obj) {
		JsonObject json = new JsonObject();
		json.addProperty("Name", obj.getIdArticolo());
		if(obj.getDescrizioneArticoloNLS().getDescrizioneEstesa() != null)
			json.addProperty("Description", obj.getDescrizioneArticoloNLS().getDescrizioneEstesa());
		else
			json.addProperty("Description", obj.getDescrizioneArticoloNLS().getDescrizione());
		json.addProperty("ProductCode", obj.getIdArticolo());
		return json.toString();
	}

	protected String controllaPresenzaArticolo(String idArticolo) {
		Map<String,String> parameters = new HashMap<String, String>();
		String query = "SELECT+Id+FROM+"+YFunzioni.PRODUCT+"+WHERE+ProductCode='"+idArticolo+"'";
		parameters.put("q", query);
		ApiResponse resp = YApiManagement.callApi(endpoint.replace("sobjects", "query"), Method.GET, MediaType.APPLICATION_JSON, null, parameters, null,apiPath);
		if(resp.success()) {
			try {	
				JSONObject body = resp.getBodyAsJSONObject();
				JSONArray records = (JSONArray) body.get("records");
				for (int i = 0; i < records.length(); i++) {
					JSONObject obj = records.getJSONObject(i);
					if(obj.get("Id") != null) {
						return obj.getString("Id");
					}else {

					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}else {
			writeLog("Prodotto non trovato: "+resp.getBodyAsString());
		}
		return null;
	}

	protected boolean insertOpportunityLineItem(String jsonRiga, OrdineVenditaRigaPrm riga) {
		boolean isOk = false;
		try {
			ApiResponse respOpportunityLineItem = YApiManagement.callApi(endpoint+idRows, Method.POST, MediaType.APPLICATION_JSON, null, null, jsonRiga,apiPath);
			if(respOpportunityLineItem.success()) {
				String opportunityLineItemId = (String) respOpportunityLineItem.getBodyAsJSONObject().get("id");
				ApiResponse readOpportunityLineItem = YApiManagement.callApi(endpoint+idRows+"/"+opportunityLineItemId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
				if(readOpportunityLineItem.success()) {
					writeLog("Riga "+riga.getKey()+" inserita correttamente");
					isOk = true;
				}
			}else {
				writeLog("Si sono verificati errori nella chiamata \n "+respOpportunityLineItem.getBodyAsString());
			}
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return isOk;
	}

	protected String getOpportunityLineItemJSON(OrdineVenditaRigaPrm riga, String opportunityId, String productId) {
		JsonObject json = new JsonObject();
		json.addProperty("OpportunityId", opportunityId);
		json.addProperty("Product2Id", productId);
		json.addProperty("UnitPrice", riga.getPrezzo() != null ? riga.getPrezzo().toString() : "");
		json.addProperty("Quantity", riga.getQtaInUMRif());
		json.addProperty("PricebookEntryId", getPriceBookEntryIdFromArticolo(riga.getArticolo()));
		return json.toString();
	}

	private String getPriceBookEntryIdFromArticolo(Articolo articolo) {
		Map<String,String> parameters = new HashMap<String, String>();
		String query = "SELECT+Id+FROM+PricebookEntry+WHERE+Pricebook2Id='"+idPricebook2+"'+AND+ProductCode='"+articolo.getIdArticolo()+"'";
		parameters.put("q", query);
		ApiResponse resp = YApiManagement.callApi(endpoint.replace("sobjects", "query"), Method.GET, MediaType.APPLICATION_JSON, null, parameters, null,apiPath);
		if(resp.success()) {
			try {	
				JSONObject body = resp.getBodyAsJSONObject();
				JSONArray records = (JSONArray) body.get("records");
				for (int i = 0; i < records.length(); i++) {
					JSONObject obj = records.getJSONObject(i);
					if(obj.get("Id") != null) {
						return obj.getString("Id");
					}else {

					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}else {
			writeLog("Impossibile trovare la riga listino"+resp.getBodyAsString());
		}
		return null;
	}

	public String getJSONAdd(OrdineVendita ord,String idClienteSalesFroce) {
		JsonObject json = new JsonObject();
		json.addProperty("AccountId", idClienteSalesFroce);
		json.addProperty("Name", ord.getCliente().getRagioneSociale());
		json.addProperty("Description", ord.getNota() != null ? ord.getNota() : "");
		json.addProperty("StageName","Negotiation/Review");
		json.addProperty("Amount",ord.getTotaleDocumento().toString());
		json.addProperty("CloseDate",ord.getDataConsegnaConfermata().toString());
		json.addProperty("Type","Existing Customer - Upgrade");
		json.addProperty("OrderNumber__c", ord.getIdNumeroOrdIC());
		switch (ord.getStatoAvanzamento()) {
		case StatoAvanzamento.TEMPLATE:
			json.addProperty("DeliveryInstallationStatus__c", "Yet to begin");
			break;
		case StatoAvanzamento.PROVVISORIO:
			json.addProperty("DeliveryInstallationStatus__c", "In progress");
			break;
		case StatoAvanzamento.DEFINITIVO:
			json.addProperty("DeliveryInstallationStatus__c", "Completed");
			break;
		default:
			break;
		}
		return json.toString();
	}

	@SuppressWarnings("unchecked")
	public List<YOrdiniInseriti> getListaOrdiniVenditaDaEsportare(){
		writeLog("\\ Reperisco gli ordini vendita stampati da esportare //");
		List<YOrdiniInseriti> lista = new ArrayList<YOrdiniInseriti>();
		try {
			String where = " "+YOrdiniInseritiTM.ID_AZIENDA+" = '"+Azienda.getAziendaCorrente()+"' AND "+YOrdiniInseritiTM.PROCESSATO+" = 'N' ";
			writeLog("\\ WHERE STRING = "+where);
			lista = YOrdiniInseriti.retrieveList(YOrdiniInseriti.class,where, "", false);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		writeLog("\\ Ho trovato :"+lista.size()+" record da processare");
		return lista;
	}

	@SuppressWarnings("rawtypes")
	public String getIdSalesForceCliente(Integer idAnagrafico) {
		String where = " "+ClienteVenditaTM.R_ANAGRAFICO+" = '"+idAnagrafico+"' ";
		try {
			Vector cli = ClienteVendita.retrieveList(where, "", false);
			if(cli.size() > 0) {
				ClienteVendita cl = (ClienteVendita) cli.get(0);
				YClientiInseriti trovoHash = (YClientiInseriti) YClientiInseriti.elementWithKey(YClientiInseriti.class, 
						KeyHelper.buildObjectKey(new String[] {
								Azienda.getAziendaCorrente(),
								cl.getIdCliente()
						}), 0);
				if(trovoHash != null) {
					return trovoHash.getIdSalesForce();
				}
			}
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return "";

	}

	protected void writeLog(String text) {
		System.out.println(text);
		getOutput().println(text);
	}

	@Override
	protected String getClassAdCollectionName() {
		return "YOpportunitaSF";
	}

}
