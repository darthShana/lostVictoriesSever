package lostVictories.dao;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.FilterBuilders.geoBoundingBoxFilter;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static com.jme3.lostVictories.network.messages.CharacterMessage.toLatitute;
import static com.jme3.lostVictories.network.messages.CharacterMessage.toLongitude;

import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.log4j.Logger;


import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jme3.lostVictories.network.messages.CharacterMessage;
import com.jme3.lostVictories.network.messages.RankMessage;
import com.jme3.lostVictories.network.messages.Vector;
import redis.clients.jedis.*;
import redis.clients.jedis.params.geo.GeoRadiusParam;

public class CharacterDAO {
	public static final double ZERO_LAT = toLatitute(new Vector(0, 0, 0));
	public static final double ZERO_LONG = toLongitude(new Vector(0, 0, 0));
	private static Logger log = Logger.getLogger(CharacterDAO.class);
	public static ObjectMapper MAPPER;
	
    static{
            MAPPER = new ObjectMapper();
            MAPPER.setVisibility(PropertyAccessor.FIELD, Visibility.ANY);
            MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            MAPPER.setSerializationInclusion(Include.NON_NULL);

    }

	private final String characterLocation;
	private final String characterStatus;

	Jedis jedis;
	private String nameSpace;

	public CharacterDAO(Jedis jedis, String nameSpace) {
		this.jedis = jedis;
		this.nameSpace = nameSpace;
		this.characterStatus = nameSpace+".characterStatus";
		this.characterLocation = nameSpace+".characterLocation";
	}
	  
	public void putCharacter(UUID uuid, CharacterMessage character) {
		Transaction transaction = jedis.multi();
		try {
			transaction.del(characterStatus+"."+character.getId().toString());
			transaction.zrem(characterLocation, character.getId().toString());
			character.getMapRepresentation().entrySet().forEach(e->{
				transaction.hset(characterStatus+"."+character.getId().toString(), e.getKey(), e.getValue());
			});
			transaction.geoadd(characterLocation, toLongitude(character.getLocation()), toLatitute(character.getLocation()), character.getId().toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			transaction.exec();
		}

	}

	public CharacterMessage getCharacter(UUID id) {
		return getCharacter(id, false);
	}

	public CharacterMessage getCharacter(UUID id, boolean watch) {
		if (watch) {
			jedis.watch(characterStatus + "." + id.toString());
		}

		Map<String, String> mapResponse = jedis.hgetAll(characterStatus + "." + id.toString());
		List<GeoCoordinate> geoLocation = jedis.geopos(characterLocation, id.toString());

		if(mapResponse !=null && !mapResponse.isEmpty() && !geoLocation.isEmpty() && geoLocation.get(0)!=null){
			try {
				return new CharacterMessage(mapResponse, geoLocation.get(0));
			} catch (IOException e) {
				throw new RuntimeException(e);
			} catch (Exception e){
                log.info("partial character:"+id+" present"+mapResponse);
                throw e;
            }
		}else{
			return null;
		}


	}

	public Set<CharacterMessage> getAllCharacters(float x, float y, float z, float range) {
		Vector v1 = new Vector(range, 0, range);
		double lat = toLatitute(v1);
		double lon = toLongitude(v1);
		double inKM = haversineInKM(ZERO_LAT, ZERO_LONG, lat, lon);

		Vector v2 = new Vector(x, y, z);
		double lat1 = toLatitute(v2);
		double lon1 = toLongitude(v2);

		Rectangle.Float boundingBox = new Rectangle2D.Float(x-range, z-range, range*2, range*2);

		List<GeoRadiusResponse> geoLocation = jedis.georadius(characterLocation, lon1, lat1, inKM, GeoUnit.KM);
		return geoLocation.stream()
				.map(r->getCharacter(UUID.fromString(r.getMemberByString())))
				.filter(c->c!=null && boundingBox.contains(c.getLocation().x, c.getLocation().z))
				.collect(Collectors.toSet());
	}

	public boolean isInRangeOf(Vector location, UUID id, float range) {
		CharacterMessage character = getCharacter(id, false);
		Rectangle.Float boundingBox = new Rectangle2D.Float(character.getLocation().x-range, character.getLocation().z-range, range*2, range*2);
		return boundingBox.contains(location.x, location.z);
	}


	static final double _eQuatorialEarthRadius = 6378.1370D;
	static final double _d2r = (Math.PI / 180D);

	public static int haversineInM(double lat1, double long1, double lat2, double long2) {
		return (int) (1000D * haversineInKM(lat1, long1, lat2, long2));
	}

	public static double haversineInKM(double lat1, double long1, double lat2, double long2) {
		double dlong = (long2 - long1) * _d2r;
		double dlat = (lat2 - lat1) * _d2r;
		double a = Math.pow(Math.sin(dlat / 2D), 2D) + Math.cos(lat1 * _d2r) * Math.cos(lat2 * _d2r)
				* Math.pow(Math.sin(dlong / 2D), 2D);
		double c = 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
		double d = _eQuatorialEarthRadius * c;

		return d;
	}
	
	public CharacterMessage findClosestCharacter(CharacterMessage c, RankMessage rank) {
		double lat1 = toLatitute(c.getLocation());
		double lon1 = toLongitude(c.getLocation());

		List<GeoRadiusResponse> characterLocation = jedis.georadius(this.characterLocation, lon1, lat1, 9000, GeoUnit.KM, GeoRadiusParam.geoRadiusParam().sortAscending());
		Optional<CharacterMessage> first = characterLocation.stream()
				.map(r -> getCharacter(UUID.fromString(r.getMemberByString())))
				.filter(cc -> rank == cc.getRank())
				.filter(i->!i.getId().equals(c.getId()))
				.findFirst();

		if(first.isPresent()){
			return first.get();
		}
		return null;
	}

	public Map<UUID, CharacterMessage> getAllCharacters(Set<UUID> ids) {
		return ids.stream().map(id->getCharacter(id, false)).filter(c->c!=null).collect(Collectors.toMap(c->c.getId(), Function.identity()));
	}

	public Set<CharacterMessage> getAllCharacters() {
		List<GeoRadiusResponse> mapResponse = jedis.georadius(this.characterLocation, 0, 0, 1000000, GeoUnit.KM);
		return mapResponse.stream().map(r->getCharacter(UUID.fromString(r.getMemberByString()), false)).filter(c->c!=null).collect(Collectors.toSet());
	}

	public void save(Collection<CharacterMessage> values) throws IOException {
		Transaction transaction = jedis.multi();
		try {
			values.forEach(character -> {
				try {
					transaction.del(characterStatus + "." + character.getId().toString());
					transaction.zrem(this.characterLocation, character.getId().toString());
					character.getMapRepresentation().entrySet().forEach(e -> {
						transaction.hset(characterStatus + "." + character.getId().toString(), e.getKey(), e.getValue());
					});
					transaction.hincrBy(characterStatus + "." + character.getId().toString(), "version", 1);
					transaction.geoadd(this.characterLocation, toLongitude(character.getLocation()), toLatitute(character.getLocation()), character.getId().toString());
				}catch (IOException e){
					throw new RuntimeException(e);
				}
			});
		} catch (Throwable e) {
			throw new RuntimeException(e);
		} finally {
			transaction.exec();
		}
	}

	public CharacterMessage updateCharacterState(CharacterMessage character) {
		Response<Long> version;
		List<Object> exec;
		Transaction transaction = jedis.multi();
		try {
			transaction.zrem(this.characterLocation, character.getId().toString());
			character.getStateUpdate().entrySet().forEach(e -> {
				transaction.hdel(characterStatus + "." + character.getId().toString(), e.getKey());
				if(e.getValue()!=null) {
					transaction.hset(characterStatus + "." + character.getId().toString(), e.getKey(), e.getValue());
				}
			});
			version = transaction.hincrBy(characterStatus + "." + character.getId().toString(), "version", 1);
			transaction.geoadd(this.characterLocation, toLongitude(character.getLocation()), toLatitute(character.getLocation()), character.getId().toString());

		} catch (Throwable e) {
			throw new RuntimeException(e);
		} finally {
			exec = transaction.exec();
		}
		if(!exec.isEmpty() && version!=null && version.get()!=null){
			character.setVersion(version.get());
			return character;
		}
		System.out.println("optimistic locking rejected character update to:"+character.getId());
		return null;
	}

	public void updateCharacterStateNoCheckout(Map<UUID, CharacterMessage> map) {
		updateCharacter(map, CharacterMessage::getStateUpdateNoCheckout);
	}

    public void saveCommandStructure(Map<UUID, CharacterMessage> map) {
        updateCharacter(map, CharacterMessage::getCommandStructureUpdate);
    }

	private void updateCharacter(Map<UUID, CharacterMessage> map, Function<CharacterMessage, Map<String, String>> updateFunction) {
		Transaction transaction = jedis.multi();
		try {
			map.values().forEach(character -> {
				transaction.zrem(this.characterLocation, character.getId().toString());
				updateFunction.apply(character).entrySet().forEach(e -> {
					transaction.hdel(characterStatus + "." + character.getId().toString(), e.getKey());
					if(e.getValue()!=null) {
						transaction.hset(characterStatus + "." + character.getId().toString(), e.getKey(), e.getValue());
					}
				});
				transaction.hincrBy(characterStatus + "." + character.getId().toString(), "version", 1);
				transaction.geoadd(this.characterLocation, toLongitude(character.getLocation()), toLatitute(character.getLocation()), character.getId().toString());
			});
		} catch (Throwable e) {
			throw new RuntimeException(e);
		} finally {
			transaction.exec();
		}
	}

	public void updateCharactersUnderCommand(CharacterMessage c) throws IOException {
		Map<UUID, CharacterMessage> map = new HashMap<>();
		map.put(c.getId(), c);
		updateCharacter(map, cc->{
			HashMap<String, String> ret = new HashMap<>();

			try {
				ret.put("unitsUnderCommand", CharacterDAO.MAPPER.writeValueAsString(cc.getUnitsUnderCommand()));
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
			return ret;
		});
	}

	public boolean delete(CharacterMessage c) {
	    log.info("deleting character:"+c.getId());
		Transaction transaction = jedis.multi();
		try {
			transaction.zrem(this.characterLocation, c.getId().toString());
			transaction.del(characterStatus + "." + c.getId().toString());
			return true;
		}finally {
			transaction.exec();
		}
	}


	public void deleteAllCharacters() {
		jedis.flushDB();
	}

}
