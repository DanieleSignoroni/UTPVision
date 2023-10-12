package it.softre.thip.base.connettori.salesforce;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.MediaType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.gson.JsonObject;
import com.thera.thermfw.batch.BatchRunnable;
import com.thera.thermfw.persist.ConnectionManager;
import com.thera.thermfw.persist.Factory;
import com.thera.thermfw.persist.PersistentObject;

import it.softre.thip.base.connettori.salesforce.generale.YPsnDatiSalesForce;
import it.softre.thip.base.connettori.salesforce.tabelle.YProdottiInseriti;
import it.softre.thip.base.connettori.utils.YApiManagement;
import it.thera.thip.api.client.ApiResponse;
import it.softre.thip.base.connettori.api.YApiRequest.Method;
import it.thera.thip.base.articolo.Articolo;
import it.thera.thip.base.articolo.ArticoloTM;
import it.thera.thip.base.azienda.Azienda;
import it.thera.thip.cs.DatiComuniEstesi;

public class YEsportatoreProdotti extends BatchRunnable{

	public String endpoint = null;

	public String id = "Product2";

	public String apiPath = null;

	public String idPricebook2 = null;

	@Override
	protected boolean run() {
		YPsnDatiSalesForce psnDati = YPsnDatiSalesForce.getCurrentPersDatiSalesForce(Azienda.getAziendaCorrente());
		boolean ret = true;
		endpoint = psnDati.getInstanceUrl() + "/sobjects/";
		apiPath = psnDati.getToken();
		String idListino = psnDati.getNomeListinoPrezzi().replace(" ", "+");
		idPricebook2 = codificaListinoTestata(idListino);
		if(idPricebook2 != null) {
			writeLog("*** ESPORT ARTICOLI - PRODOTTI SALES FORCE ***");
			if(psnDati != null) {
				List<Articolo> prodotti = getListaArticoliValidi();
				for (Iterator<Articolo> iterator = prodotti.iterator(); iterator.hasNext();) {
					writeLog("");
					try {
						Articolo articolo = (Articolo) iterator.next();
						writeLog("--------- Processo l'articolo : "+articolo.getKey()+" -------------");
						String json = getJSONAdd(articolo);
						YProdottiInseriti tab = getProdottoInseritoByKey(articolo.getKey());
						if(tab == null) {
							if(!esisteGia(articolo.getIdArticolo())) { //ulteriore controllo per evitare di duplicare...
								writeLog("L'articolo non e' ancora stato esportato in Sales Force, procedo all'inserimento");
								ApiResponse response = YApiManagement.callApi(endpoint+id, Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
								if(response.success()) {
									String productId = (String) response.getBodyAsJSONObject().get("id");
									ApiResponse read = YApiManagement.callApi(endpoint+id+"/"+productId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
									if(read.success()) {
										writeLog("Articolo inserito in Sales Force correttamente");
										YProdottiInseriti ins = (YProdottiInseriti) Factory.createObject(YProdottiInseriti.class);
										ins.setKey(articolo.getKey());
										ins.setIdSalesForce(productId);
										if(ins.save() >= 0) {
											writeLog("Popolata correttamente la tabella di appoggio : "+ins.getAbstractTableManager().getMainTableName());
											ConnectionManager.commit();
										}else {
											writeLog("Vi sono stati errori nella popolazione della tabella di appoggio : "+ins.getAbstractTableManager().getMainTableName());
											ConnectionManager.rollback();
										}
										inserisciRigaListino(productId,articolo);
									}
								}else {
									writeLog("Impossibile inserire l'articolo, errori: \n"+response.getBodyAsString());
								}
							}else {
								writeLog("L'articolo esiste gia' in sales force, non verra' importato");
							}
						}else {
							ApiResponse read = YApiManagement.callApi(endpoint+id+"/"+tab.getIdSalesForce(), Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
							if(read.success()) {
								writeLog("L'articolo e' gia presente in Sales Force \n id = "+tab.getIdSalesForce()+", procedo con l'aggiornamento");
								//esiste davvero, vado in edit
								String command = "curl -X PATCH "+endpoint+id+"/"+tab.getIdSalesForce()+"  -H \"Content-Type: application/json\"     -H \"Authorization: Bearer "+this.apiPath+"\" ";
								String formattedJson = json.replace("\"", "\"\"\"");
								command += " -d "+formattedJson+" ";
								Process powerShellProcess = Runtime.getRuntime().exec(command);
								powerShellProcess.getOutputStream().close();
								int exitValue = -1;
								try {
									exitValue = powerShellProcess.waitFor();
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
								if(exitValue == 0) {
									writeLog("Prodotto aggiornato correttamente");
									if(tab.save() >= 0) {
										writeLog("Aggiornata correttamente la tabella di appoggio : "+tab.getAbstractTableManager().getMainTableName());
										ConnectionManager.commit();
									}else {
										writeLog("Vi sono stati errori nell'aggiornamento della tabella di appoggio : "+tab.getAbstractTableManager().getMainTableName());
										ConnectionManager.rollback();
									}
								}else {
									writeLog("Prodotto aggiornato con errori");
								}
							}else {
								writeLog(read.getBodyAsString());
							}
							inserisciRigaListino(tab.getIdSalesForce(),articolo);
						}
						writeLog("--------- Ho finito di processare l'articolo : "+articolo.getKey()+" -------------");
					} catch (JSONException e) {
						ret = false;
						e.printStackTrace();
					} catch (SQLException e) {
						ret = false;
						e.printStackTrace();
					} catch (IOException e) {
						ret = false;
						e.printStackTrace();
					}
				}
			}
		}
		return ret;
	}

	protected boolean esisteGia(String idArticolo) {
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
						return true;
					}else {

					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}else {
			writeLog("Prodotto non trovato: "+resp.getBodyAsString());
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

	protected void inserisciRigaListino(String productId, Articolo articolo) {
		String json = getJSONRigaListino(productId,articolo);
		try {
			String priceBookEntryId = getPriceBookEntryIdFromArticolo(articolo);
			if(priceBookEntryId == null) {
				ApiResponse response = YApiManagement.callApi(endpoint+"PriceBookEntry", Method.POST, MediaType.APPLICATION_JSON, null, null, json,apiPath);
				if(response.success()) {
					priceBookEntryId = (String) response.getBodyAsJSONObject().get("id");
					ApiResponse read = YApiManagement.callApi(endpoint+"PriceBookEntry"+"/"+priceBookEntryId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
					if(read.success()) {
						writeLog("Riga listino inserita correttamente");
					}
				}else {
					writeLog("Impossibile inserire riga listino: "+response.getBodyAsString());
				}
			}else {
				ApiResponse read = YApiManagement.callApi(endpoint+"PriceBookEntry"+"/"+priceBookEntryId, Method.GET, MediaType.APPLICATION_JSON, null, null, null,apiPath);
				if(read.success()) {
					json = "{\"UnitPrice\":\"0\"}";
					writeLog("Riga listino e' gia presente in Sales Force \n id = "+priceBookEntryId+", procedo con l'aggiornamento");
					String command = "curl -X PATCH "+endpoint+"PriceBookEntry"+"/"+priceBookEntryId+"  -H \"Content-Type: application/json\"     -H \"Authorization: Bearer "+this.apiPath+"\" ";
					String formattedJson = json.replace("\"", "\"\"\"");
					command += " -d "+formattedJson+" ";
					Process powerShellProcess = Runtime.getRuntime().exec(command);
					powerShellProcess.getOutputStream().close();
					int exitValue = -1;
					try {
						exitValue = powerShellProcess.waitFor();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					if(exitValue == 0) {
						writeLog("Prodotto aggiornato correttamente");
					}else {
						writeLog("Prodotto aggiornato con errori");
					}
				}else {
					writeLog("Errori nella lettura della riga listino: "+read.getBodyAsString());
				}
			}
		}catch (JSONException e) {
			e.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}

	protected String getPriceBookEntryIdFromArticolo(Articolo articolo) {
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

	protected String getJSONRigaListino(String productId, Articolo articolo) {
		JsonObject json = new JsonObject();
		json.addProperty("Pricebook2Id", idPricebook2);
		json.addProperty("Product2Id", productId);
		json.addProperty("UnitPrice", "0");
		json.addProperty("IsActive", true);
		return json.toString();
	}

	public static YProdottiInseriti getProdottoInseritoByKey(String key) {
		try {
			return (YProdottiInseriti) YProdottiInseriti.elementWithKey(YProdottiInseriti.class,key, PersistentObject.NO_LOCK);
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return null;
	}

	public String getJSONAdd(Articolo obj) {
		JsonObject json = new JsonObject();
		json.addProperty("Name", obj.getIdArticolo());
		if(obj.getDescrizioneArticoloNLS().getDescrizioneEstesa() != null)
			json.addProperty("Description", obj.getDescrizioneArticoloNLS().getDescrizioneEstesa());
		else
			json.addProperty("Description", obj.getDescrizioneArticoloNLS().getDescrizione());
		json.addProperty("ProductCode", obj.getIdArticolo());
		return json.toString();
	}


	@SuppressWarnings("unchecked")
	public List<Articolo> getListaArticoliValidi(){
		writeLog("\\ Reperisco gli articoli da esportare //");
		List<Articolo> lista = new ArrayList<Articolo>();
		try {
			String where = " "+ArticoloTM.ID_AZIENDA+" = '"+Azienda.getAziendaCorrente()+"' AND "+ArticoloTM.STATO+" = '"+DatiComuniEstesi.VALIDO+"' ";
			writeLog("\\ WHERE STRING = "+where);
			lista = Articolo.retrieveList(Articolo.class,where, "", false);
			lista = lista.subList(0, 50);
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

	@Override
	protected String getClassAdCollectionName() {
		return "YProdottiSF";
	}

	protected void writeLog(String text) {
		System.out.println(text);
		getOutput().println(text);
	}
}
